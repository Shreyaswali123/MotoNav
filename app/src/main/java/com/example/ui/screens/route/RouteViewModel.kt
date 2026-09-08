package com.example.ui.screens.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ble.BleRepository
import com.example.ble.BleRepositoryProvider
import com.example.data.DefaultLocationSearchRepository
import com.example.data.LocationSearchRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

sealed interface RouteGenerationState {
    object Idle : RouteGenerationState
    object GeneratingRoute : RouteGenerationState
    data class RouteGenerationError(val message: String) : RouteGenerationState
    object Success : RouteGenerationState
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
    val routeGenerationError: String? = null
)

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

    private val _locationSearchResults = MutableStateFlow<List<SearchLocation>>(locationSearchRepository.getPopularLocations())
    val locationSearchResults: StateFlow<List<SearchLocation>> = _locationSearchResults.asStateFlow()

    private val _isSearchingLocations = MutableStateFlow(false)
    val isSearchingLocations: StateFlow<Boolean> = _isSearchingLocations.asStateFlow()

    val popularLocations: List<SearchLocation> get() = locationSearchRepository.getPopularLocations()

    val isSendRouteEnabled: StateFlow<Boolean> = combine(
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

    fun selectStartLocation(point: RoutePoint) {
        _startLocation.value = point
        validateLocations(point, _destination.value)
        updateSelectedRouteEndpoints()
    }

    fun selectDestination(point: RoutePoint) {
        _destination.value = point
        validateLocations(_startLocation.value, point)
        updateSelectedRouteEndpoints()
    }

    fun clearStartLocation() {
        _startLocation.value = null
        _locationValidationError.value = null
    }

    fun clearDestination() {
        _destination.value = null
        _locationValidationError.value = null
    }

    fun swapLocations() {
        val currentStart = _startLocation.value
        val currentDest = _destination.value
        _startLocation.value = currentDest
        _destination.value = currentStart
        validateLocations(currentDest, currentStart)
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
                destination = dest
            )
        } else if (start != null) {
            _selectedRoute.value = _selectedRoute.value.copy(startLocation = start)
        } else if (dest != null) {
            _selectedRoute.value = _selectedRoute.value.copy(destination = dest)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        _searchResults.value = routeRepository.searchDestinations(query)
    }

    fun selectRoute(routeId: String) {
        routeRepository.selectRoute(routeId)
        val route = routeRepository.availableRoutes.find { it.id == routeId } ?: routeRepository.selectedRoute.value
        _selectedRoute.value = route
        _startLocation.value = route.startLocation
        _destination.value = route.destination
        _locationValidationError.value = null
    }

    fun searchLocations(query: String) {
        viewModelScope.launch {
            _isSearchingLocations.value = true
            val results = locationSearchRepository.searchLocations(query)
            _locationSearchResults.value = results
            _isSearchingLocations.value = false
        }
    }

    fun sendRouteToMotoNav() {
        val start = _startLocation.value
        val dest = _destination.value
        if (start == null || dest == null) {
            _routeGenerationError.value = "Start and destination points must both be selected"
            _routeGenerationState.value = RouteGenerationState.RouteGenerationError("Start and destination points must both be selected")
            return
        }
        if (areLocationsEqual(start, dest)) {
            val error = "Start and destination cannot be the same location"
            _locationValidationError.value = error
            _routeGenerationError.value = error
            _routeGenerationState.value = RouteGenerationState.RouteGenerationError(error)
            return
        }

        viewModelScope.launch {
            _isGeneratingRoute.value = true
            _routeGenerationError.value = null
            _routeGenerationState.value = RouteGenerationState.GeneratingRoute

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
                _routeGenerationError.value = errorMsg
                _routeGenerationState.value = RouteGenerationState.RouteGenerationError(errorMsg)
                _isGeneratingRoute.value = false
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

            _selectedRoute.value = _selectedRoute.value.copy(
                startLocation = start,
                destination = dest,
                totalDistanceMeters = conversion.totalDistanceMeters,
                estimatedDurationSeconds = conversion.durationSeconds,
                waypoints = conversion.simplifiedPoints,
                maneuvers = mappedManeuvers
            )

            _routeGenerationState.value = RouteGenerationState.Success
            _isGeneratingRoute.value = false

            bleRepository.transferSerializedRoute(
                serialized.binary,
                serialized.crc32
            )
        }
    }
}
