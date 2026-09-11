package com.example.ui.screens.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ble.BleRepository
import com.example.ble.BleRepositoryProvider
import com.example.data.DefaultLocationSearchRepository
import com.example.data.LocationSearchRepository
import com.example.data.LocationSearchResult
import com.example.data.RouteRepository
import com.example.data.SampleRouteRepository
import com.example.data.SearchLocation
import com.example.model.ConnectionState
import com.example.model.Maneuver
import com.example.model.ManeuverType
import com.example.model.Route
import com.example.model.RoutePoint
import com.example.model.RouteTransferProgress
import com.example.model.UnitSystem
import com.example.network.ValhallaRouteRepository
import com.example.settings.InMemorySettingsRepository
import com.example.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

sealed interface RouteGenerationState {
    object Idle : RouteGenerationState
    object GeneratingRoute : RouteGenerationState
    data class RouteGenerationError(val message: String) : RouteGenerationState
    object Success : RouteGenerationState
}

/**
 * Retains the exact generated route, its serialized binary payload, and CRC32
 * tied to the specific origin and destination coordinates used during generation.
 */
data class GeneratedRouteState(
    val route: Route,
    val serializedBinary: ByteArray,
    val crc32: Long,
    val origin: RoutePoint,
    val destination: RoutePoint
) {
    fun matchesEndpoints(start: RoutePoint?, dest: RoutePoint?): Boolean {
        if (start == null || dest == null) return false
        val startLatDiff = abs(origin.latitude - start.latitude)
        val startLonDiff = abs(origin.longitude - start.longitude)
        val destLatDiff = abs(destination.latitude - dest.latitude)
        val destLonDiff = abs(destination.longitude - dest.longitude)
        return startLatDiff < 0.0001 && startLonDiff < 0.0001 &&
                destLatDiff < 0.0001 && destLonDiff < 0.0001
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GeneratedRouteState
        if (route != other.route) return false
        if (!serializedBinary.contentEquals(other.serializedBinary)) return false
        if (crc32 != other.crc32) return false
        if (origin != other.origin) return false
        if (destination != other.destination) return false
        return true
    }

    override fun hashCode(): Int {
        var result = route.hashCode()
        result = 31 * result + serializedBinary.contentHashCode()
        result = 31 * result + crc32.hashCode()
        result = 31 * result + origin.hashCode()
        result = 31 * result + destination.hashCode()
        return result
    }
}

data class RouteUiState(
    val searchQuery: String = "",
    val availableRoutes: List<Route> = emptyList(),
    val filteredRoutes: List<Route> = emptyList(),
    val selectedRoute: Route,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val transferProgress: RouteTransferProgress = RouteTransferProgress(),
    val unitSystem: UnitSystem = UnitSystem.KILOMETERS,
    val isTransferring: Boolean = false,
    val routeGenerationState: RouteGenerationState = RouteGenerationState.Idle,
    val routeGenerationError: String? = null,
    val generatedRoute: GeneratedRouteState? = null
)

val RouteTransferProgress.isTransferring: Boolean
    get() = (totalPackets > 0 && currentPacket < totalPackets) ||
        stepDescription.equals("Transferring", ignoreCase = true) ||
        stepDescription.startsWith("Transferring", ignoreCase = true) ||
        (percentage in 1..99)

fun RouteTransferProgress(isTransferring: Boolean, percentage: Int = 0): RouteTransferProgress {
    return RouteTransferProgress(
        percentage = percentage,
        currentPacket = if (isTransferring) 1 else 0,
        totalPackets = if (isTransferring) 2 else 0,
        stepDescription = if (isTransferring) "Transferring" else "Idle"
    )
}

class RouteViewModel(
    private val routeRepository: RouteRepository = SampleRouteRepository.instance,
    private val bleRepository: BleRepository = BleRepositoryProvider.instance,
    private val settingsRepository: SettingsRepository = InMemorySettingsRepository.instance,
    private val valhallaRouteRepository: ValhallaRouteRepository = ValhallaRouteRepository.instance,
    private val locationSearchRepository: LocationSearchRepository = DefaultLocationSearchRepository.instance,
    initialStartLocation: RoutePoint? = null,
    initialDestination: RoutePoint? = null
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _startLocation = MutableStateFlow<RoutePoint?>(initialStartLocation ?: routeRepository.selectedRoute.value.startLocation)
    val startLocation: StateFlow<RoutePoint?> = _startLocation.asStateFlow()

    private val _destination = MutableStateFlow<RoutePoint?>(initialDestination ?: routeRepository.selectedRoute.value.destination)
    val destination: StateFlow<RoutePoint?> = _destination.asStateFlow()

    private val _locationValidationError = MutableStateFlow<String?>(null)
    val locationValidationError: StateFlow<String?> = _locationValidationError.asStateFlow()

    private val _selectedRoute = MutableStateFlow(routeRepository.selectedRoute.value)
    val selectedRoute: StateFlow<Route> = _selectedRoute.asStateFlow()

    private val _generatedRoute = MutableStateFlow<GeneratedRouteState?>(null)
    val generatedRoute: StateFlow<GeneratedRouteState?> = _generatedRoute.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = bleRepository.connectionState
    val transferProgress: StateFlow<RouteTransferProgress> = bleRepository.transferProgress
    val unitSystem: StateFlow<UnitSystem> = settingsRepository.preferences
        .map { it.unitSystem }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UnitSystem.KILOMETERS)

    private val _searchResults = MutableStateFlow(routeRepository.availableRoutes)
    val searchResults: StateFlow<List<Route>> = _searchResults.asStateFlow()

    private val _routeGenerationState = MutableStateFlow<RouteGenerationState>(RouteGenerationState.Idle)
    val routeGenerationState: StateFlow<RouteGenerationState> = _routeGenerationState.asStateFlow()

    private val _routeGenerationError = MutableStateFlow<String?>(null)
    val routeGenerationError: StateFlow<String?> = _routeGenerationError.asStateFlow()

    private val _isGeneratingRoute = MutableStateFlow(false)
    val isGeneratingRoute: StateFlow<Boolean> = _isGeneratingRoute.asStateFlow()
    private val isGenerationInProgress = AtomicBoolean(false)

    private val _locationSearchResults = MutableStateFlow<List<SearchLocation>>(locationSearchRepository.getPopularLocations())
    val locationSearchResults: StateFlow<List<SearchLocation>> = _locationSearchResults.asStateFlow()

    private val _isSearchingLocations = MutableStateFlow(false)
    val isSearchingLocations: StateFlow<Boolean> = _isSearchingLocations.asStateFlow()

    private val _locationSearchError = MutableStateFlow<String?>(null)
    val locationSearchError: StateFlow<String?> = _locationSearchError.asStateFlow()

    private var locationSearchJob: Job? = null

    val popularLocations: List<SearchLocation> get() = locationSearchRepository.getPopularLocations()

    val isGenerateRouteEnabled: StateFlow<Boolean> = combine(
        _startLocation,
        _destination,
        _isGeneratingRoute,
        bleRepository.connectionState
    ) { start, dest, generating, connState ->
        start != null &&
            dest != null &&
            !areLocationsEqual(start, dest) &&
            !generating &&
            connState != ConnectionState.Transferring
    }.stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = false)

    val isSendRouteEnabled: StateFlow<Boolean> = combine(
        _startLocation,
        _destination,
        _isGeneratingRoute,
        _generatedRoute,
        bleRepository.connectionState
    ) { start, dest, generating, genRoute, connState ->
        val isBleReady = connState == ConnectionState.Connected || connState == ConnectionState.RouteReady
        start != null &&
            dest != null &&
            !areLocationsEqual(start, dest) &&
            !generating &&
            connState != ConnectionState.Transferring &&
            isBleReady &&
            genRoute != null &&
            genRoute.matchesEndpoints(start, dest)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = false)

    init {
        validateLocations(_startLocation.value, _destination.value)
    }

    private fun areLocationsEqual(p1: RoutePoint?, p2: RoutePoint?): Boolean {
        if (p1 == null || p2 == null) return false
        val latDiff = abs(p1.latitude - p2.latitude)
        val lonDiff = abs(p1.longitude - p2.longitude)
        if (latDiff < 0.0001 && lonDiff < 0.0001) return true
        if (!p1.name.isNullOrBlank() && !p2.name.isNullOrBlank() && p1.name.equals(p2.name, ignoreCase = true)) return true
        return false
    }

    private fun validateLocations(start: RoutePoint?, dest: RoutePoint?): Boolean {
        if (start != null && dest != null && areLocationsEqual(start, dest)) {
            _locationValidationError.value = "Start and destination cannot be the same location"
            return false
        }
        _locationValidationError.value = null
        return true
    }

    private fun invalidateGeneratedRoute() {
        _generatedRoute.value = null
        if (_routeGenerationState.value == RouteGenerationState.Success) {
            _routeGenerationState.value = RouteGenerationState.Idle
        }
        _routeGenerationError.value = null
    }

    fun selectStartLocation(point: RoutePoint): Boolean {
        if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
            point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) {
            _locationValidationError.value = "Start location coordinates are invalid"
            return false
        }
        _startLocation.value = point
        validateLocations(point, _destination.value)
        invalidateGeneratedRoute()
        updateSelectedRouteEndpoints()
        return true
    }

    fun selectDestination(point: RoutePoint): Boolean {
        if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
            point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) {
            _locationValidationError.value = "Destination coordinates are invalid"
            return false
        }
        _destination.value = point
        validateLocations(_startLocation.value, point)
        invalidateGeneratedRoute()
        updateSelectedRouteEndpoints()
        return true
    }

    fun selectStartLocation(location: SearchLocation): Boolean {
        if (!location.isValid()) {
            _locationValidationError.value = "Start location coordinates are invalid"
            return false
        }
        return selectStartLocation(location.toRoutePoint())
    }

    fun selectDestination(location: SearchLocation): Boolean {
        if (!location.isValid()) {
            _locationValidationError.value = "Destination coordinates are invalid"
            return false
        }
        return selectDestination(location.toRoutePoint())
    }

    fun clearStartLocation() {
        _startLocation.value = null
        _locationValidationError.value = null
        invalidateGeneratedRoute()
        _selectedRoute.value = _selectedRoute.value.copy(
            waypoints = emptyList(),
            maneuvers = emptyList(),
            totalDistanceMeters = 0,
            estimatedDurationSeconds = 0
        )
    }

    fun clearDestination() {
        _destination.value = null
        _locationValidationError.value = null
        invalidateGeneratedRoute()
        _selectedRoute.value = _selectedRoute.value.copy(
            waypoints = emptyList(),
            maneuvers = emptyList(),
            totalDistanceMeters = 0,
            estimatedDurationSeconds = 0
        )
    }

    fun swapLocations() {
        val currentStart = _startLocation.value
        val currentDest = _destination.value
        _startLocation.value = currentDest
        _destination.value = currentStart
        validateLocations(currentDest, currentStart)
        invalidateGeneratedRoute()
        updateSelectedRouteEndpoints()
    }

    private fun updateSelectedRouteEndpoints() {
        val start = _startLocation.value
        val dest = _destination.value
        if (start != null && dest != null) {
            val title = "${start.name ?: "Start"} to ${dest.name ?: "Destination"}"
            _selectedRoute.value = _selectedRoute.value.copy(
                title = title,
                summary = "Custom route from ${start.name ?: "origin"} to ${dest.name ?: "destination"}",
                startLocation = start,
                destination = dest,
                waypoints = emptyList(),
                maneuvers = emptyList(),
                totalDistanceMeters = 0,
                estimatedDurationSeconds = 0
            )
        } else if (start != null) {
            _selectedRoute.value = _selectedRoute.value.copy(
                startLocation = start,
                waypoints = emptyList(),
                maneuvers = emptyList(),
                totalDistanceMeters = 0,
                estimatedDurationSeconds = 0
            )
        } else if (dest != null) {
            _selectedRoute.value = _selectedRoute.value.copy(
                destination = dest,
                waypoints = emptyList(),
                maneuvers = emptyList(),
                totalDistanceMeters = 0,
                estimatedDurationSeconds = 0
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        _searchResults.value = routeRepository.searchDestinations(query)
    }

    fun selectRoute(routeId: String) {
        routeRepository.selectRoute(routeId)
        val route = routeRepository.availableRoutes.find { it.id == routeId } ?: routeRepository.selectedRoute.value
        _selectedRoute.value = route.copy(
            waypoints = emptyList(),
            maneuvers = emptyList(),
            totalDistanceMeters = 0,
            estimatedDurationSeconds = 0
        )
        _startLocation.value = route.startLocation
        _destination.value = route.destination
        _locationValidationError.value = null
        invalidateGeneratedRoute()
    }

    fun searchLocations(query: String, debounceMs: Long = 400L) {
        locationSearchJob?.cancel()
        _locationSearchError.value = null

        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _isSearchingLocations.value = false
            _locationSearchResults.value = locationSearchRepository.getPopularLocations()
            _locationSearchError.value = null
            return
        }

        _isSearchingLocations.value = true
        locationSearchJob = viewModelScope.launch {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            val currentStart = _startLocation.value
            when (val result = locationSearchRepository.search(trimmed, currentStart?.latitude, currentStart?.longitude)) {
                is LocationSearchResult.Success -> {
                    _locationSearchResults.value = result.locations
                    _locationSearchError.value = null
                }
                is LocationSearchResult.Empty -> {
                    _locationSearchResults.value = emptyList()
                    _locationSearchError.value = null
                }
                is LocationSearchResult.NetworkError -> {
                    _locationSearchResults.value = result.fallbackLocations
                    _locationSearchError.value = result.message
                }
                is LocationSearchResult.RateLimited -> {
                    _locationSearchResults.value = result.fallbackLocations
                    _locationSearchError.value = result.message
                }
                is LocationSearchResult.MalformedResponse -> {
                    _locationSearchResults.value = emptyList()
                    _locationSearchError.value = result.message
                }
            }
            _isSearchingLocations.value = false
        }
    }

    /**
     * Generates a motorcycle route between selected origin and destination using Valhalla,
     * simplifies geometry, serializes to MotoNav v1 format, and stores the resulting route
     * in ViewModel state for preview.
     *
     * Does NOT transmit over BLE.
     */
    fun generateRoute() {
        val start = _startLocation.value
        val dest = _destination.value
        if (start == null || dest == null) {
            val error = "Start and destination points must both be selected"
            _routeGenerationError.value = error
            _routeGenerationState.value = RouteGenerationState.RouteGenerationError(error)
            return
        }
        if (areLocationsEqual(start, dest)) {
            val error = "Start and destination cannot be the same location"
            _locationValidationError.value = error
            _routeGenerationError.value = error
            _routeGenerationState.value = RouteGenerationState.RouteGenerationError(error)
            return
        }

        val isTransferring = bleRepository.connectionState.value == ConnectionState.Transferring ||
            bleRepository.transferProgress.value.isTransferring
        if (isTransferring) {
            val error = "Cannot generate route while transfer is in progress"
            _routeGenerationError.value = error
            _routeGenerationState.value = RouteGenerationState.RouteGenerationError(error)
            return
        }

        if (!isGenerationInProgress.compareAndSet(false, true)) {
            return
        }
        _isGeneratingRoute.value = true

        viewModelScope.launch {
            _routeGenerationError.value = null
            _routeGenerationState.value = RouteGenerationState.GeneratingRoute

            try {
                val routeId = _selectedRoute.value.id.toLongOrNull() ?: 1L
                val result = try {
                    valhallaRouteRepository.fetchRoute(
                        origin = start,
                        destination = dest,
                        routeId = routeId
                    )
                } catch (e: Throwable) {
                    Result.failure(e)
                }

                if (result.isFailure) {
                    val errorMsg = result.exceptionOrNull()?.message ?: "Failed to generate route from Valhalla"
                    _generatedRoute.value = null
                    _selectedRoute.value = _selectedRoute.value.copy(
                        waypoints = emptyList(),
                        maneuvers = emptyList(),
                        totalDistanceMeters = 0,
                        estimatedDurationSeconds = 0
                    )
                    _routeGenerationError.value = errorMsg
                    _routeGenerationState.value = RouteGenerationState.RouteGenerationError(errorMsg)
                    return@launch
                }

                val conversion = result.getOrThrow()
                val serialized = conversion.serializedRoute

                val mappedManeuvers = conversion.maneuvers.mapIndexed { index, record ->
                    val point = conversion.simplifiedPoints.getOrElse(record.pointIndex) {
                        conversion.simplifiedPoints.lastOrNull() ?: dest
                    }
                    val maneuverType = when (record.motoNavType) {
                        1.toByte() -> ManeuverType.TURN_LEFT
                        2.toByte() -> ManeuverType.TURN_RIGHT
                        5.toByte() -> ManeuverType.ARRIVE
                        else -> ManeuverType.STRAIGHT
                    }
                    val instructionText = record.instruction ?: when (record.motoNavType) {
                        1.toByte() -> "Turn left"
                        2.toByte() -> "Turn right"
                        5.toByte() -> "Arrive at destination"
                        else -> "Continue straight"
                    }
                    Maneuver(
                        id = "gen_${index}_${record.pointIndex}",
                        type = maneuverType,
                        instruction = instructionText,
                        roadName = "",
                        distanceMeters = record.distanceMeters,
                        point = point
                    )
                }

                val generated = _selectedRoute.value.copy(
                    startLocation = start,
                    destination = dest,
                    totalDistanceMeters = conversion.totalDistanceMeters,
                    estimatedDurationSeconds = conversion.durationSeconds,
                    waypoints = conversion.simplifiedPoints,
                    maneuvers = mappedManeuvers
                )

                _selectedRoute.value = generated
                _generatedRoute.value = GeneratedRouteState(
                    route = generated,
                    serializedBinary = serialized.binary,
                    crc32 = serialized.crc32,
                    origin = start,
                    destination = dest
                )

                _routeGenerationState.value = RouteGenerationState.Success
            } finally {
                _isGeneratingRoute.value = false
                isGenerationInProgress.set(false)
            }
        }
    }

    /**
     * Transmits the ALREADY GENERATED route payload to the ESP32 MotoNav device over BLE.
     * Does NOT call Valhalla again.
     */
    fun sendRouteToMotoNav() {
        val start = _startLocation.value
        val dest = _destination.value
        val currentGenerated = _generatedRoute.value

        if (start == null || dest == null) {
            val error = "Start and destination points must both be selected"
            _routeGenerationError.value = error
            _routeGenerationState.value = RouteGenerationState.RouteGenerationError(error)
            return
        }
        if (areLocationsEqual(start, dest)) {
            val error = "Start and destination cannot be the same location"
            _locationValidationError.value = error
            _routeGenerationError.value = error
            _routeGenerationState.value = RouteGenerationState.RouteGenerationError(error)
            return
        }
        if (currentGenerated == null || !currentGenerated.matchesEndpoints(start, dest)) {
            val error = "Please generate the route before sending to MotoNav"
            _routeGenerationError.value = error
            _routeGenerationState.value = RouteGenerationState.RouteGenerationError(error)
            return
        }

        val conn = bleRepository.connectionState.value
        if (conn != ConnectionState.Connected && conn != ConnectionState.RouteReady) {
            val error = "MotoNav device is not connected"
            _routeGenerationError.value = error
            return
        }

        // Send the exact stored serialized binary and CRC to the ESP32
        // Do NOT call Valhalla
        bleRepository.transferSerializedRoute(
            currentGenerated.serializedBinary,
            currentGenerated.crc32
        )
    }

    fun cancelTransfer() {
        bleRepository.cancelTransfer()
    }
}
