package com.example.ui

import com.example.ble.BleRepository
import com.example.data.LocationSearchRepository
import com.example.data.SampleRouteRepository
import com.example.data.SearchLocation
import com.example.model.BleDiagnostics
import com.example.model.ConnectionState
import com.example.model.MotoNavDevice
import com.example.model.RoutePoint
import com.example.model.RouteTransferProgress
import com.example.model.UnitSystem
import com.example.network.ValhallaRouteRepository
import com.example.route.RouteConversionResult
import com.example.route.SerializedMotoNavRoute
import com.example.settings.InMemorySettingsRepository
import com.example.ui.screens.home.HomeViewModel
import com.example.ui.screens.route.RouteViewModel
import com.example.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cross-ViewModel State & UnitSystem Integration Tests (Issue #12B).
 *
 * Exercises the cross-component state synchronization between:
 * SettingsViewModel
 *     ↓
 * InMemorySettingsRepository
 *     ↓
 * RouteViewModel & HomeViewModel (unitSystem StateFlow)
 *
 * Verifies:
 * 1. UnitSystem changes propagate reactively to all ViewModels without divergence.
 * 2. Toggling UnitSystem preserves existing active and generated route state
 *    (Issue #7A invariant: RouteViewModel.selectedRoute === HomeViewModel.activeRoute).
 * 3. Default production navigation wiring uses the shared InMemorySettingsRepository singleton.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossViewModelStateIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeBleRepository : BleRepository {
        override val connectionState = MutableStateFlow(ConnectionState.Connected)
        override val connectedDevice = MutableStateFlow<MotoNavDevice?>(null)
        override val diagnostics = MutableStateFlow(BleDiagnostics())
        override val transferProgress = MutableStateFlow(RouteTransferProgress())
        override val lastError = MutableStateFlow<String?>(null)

        override fun connect(deviceId: String) {}
        override fun disconnect() {}
        override fun readStatus() {}
        override fun sendTestRoute() {}
        override fun transferSerializedRoute(binary: ByteArray, crc32: Long) {}
        override fun cancelTransfer() {}
        override fun resetError() {}
        override fun simulateError() {}
    }

    private class FakeValhallaRouteRepository : ValhallaRouteRepository() {
        var resultToReturn: Result<RouteConversionResult>? = null

        override suspend fun fetchRoute(
            origin: RoutePoint,
            destination: RoutePoint,
            routeId: Long
        ): Result<RouteConversionResult> {
            return resultToReturn ?: Result.failure(IllegalStateException("No fake result set"))
        }
    }

    private class FakeLocationSearchRepository : LocationSearchRepository {
        override fun getPopularLocations(): List<SearchLocation> = emptyList()
        override suspend fun search(query: String) = com.example.data.LocationSearchResult.Success(emptyList())
        override suspend fun searchLocations(query: String): List<SearchLocation> = emptyList()
    }

    private fun createValidConversionResult(): RouteConversionResult {
        // Valid MotoNav v1 fixture (version 1, 2 points, 0 maneuvers)
        val binary = byteArrayOf(
            0x01,                   // version 1
            0x2A, 0x00, 0x00, 0x00, // route ID 42 (uint32 LE)
            0x02, 0x00,             // 2 points (uint16 LE)
            0x00, 0x00,             // 0 maneuvers (uint16 LE)
            // Point 0: 15.35, 75.1491 -> lat*1e7=153500000 (0x09264160), lon*1e7=751491000 (0x2CCBCEB8)
            0x60, 0x41, 0x26, 0x09,
            0xB8.toByte(), 0xCE.toByte(), 0xCB.toByte(), 0x2C,
            // Point 1: 15.44, 75.0045 -> lat*1e7=154400000 (0x0933FB00), lon*1e7=750045000 (0x2CB5C348)
            0x00, 0xFB.toByte(), 0x33, 0x09,
            0x48, 0xC3.toByte(), 0xB5.toByte(), 0x2C
        )
        return RouteConversionResult(
            serializedRoute = SerializedMotoNavRoute(
                binary = binary,
                crc32 = 0xA1B2C3D4L
            ),
            decodedPoints = listOf(
                RoutePoint(latitude = 15.35, longitude = 75.1491, name = "Hubballi"),
                RoutePoint(latitude = 15.44, longitude = 75.0045, name = "Dharwad")
            ),
            simplifiedPoints = listOf(
                RoutePoint(latitude = 15.35, longitude = 75.1491, name = "Hubballi"),
                RoutePoint(latitude = 15.44, longitude = 75.0045, name = "Dharwad")
            ),
            maneuvers = emptyList(),
            totalDistanceMeters = 21500,
            durationSeconds = 1800
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * TEST 1: UnitSystem propagates reactively across all ViewModels.
     *
     * Verifies that when SettingsViewModel updates UnitSystem via a shared
     * InMemorySettingsRepository, both RouteViewModel and HomeViewModel
     * observe the updated UnitSystem without divergence.
     */
    @Test
    fun testUnitSystem_PropagatesReactivelyAcrossAllViewModels() = runTest(testDispatcher) {
        val sharedSettingsRepo = InMemorySettingsRepository()
        sharedSettingsRepo.setUnitSystem(UnitSystem.KILOMETERS)

        val settingsViewModel = SettingsViewModel(settingsRepository = sharedSettingsRepo)
        val routeViewModel = RouteViewModel(
            routeRepository = SampleRouteRepository.instance,
            bleRepository = FakeBleRepository(),
            settingsRepository = sharedSettingsRepo,
            valhallaRouteRepository = FakeValhallaRouteRepository(),
            locationSearchRepository = FakeLocationSearchRepository()
        )
        val homeViewModel = HomeViewModel(
            bleRepository = FakeBleRepository(),
            activeRoute = routeViewModel.selectedRoute,
            settingsRepository = sharedSettingsRepo
        )

        // Activate WhileSubscribed(5000) sharing on ViewModel StateFlows
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            routeViewModel.unitSystem.collect()
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            homeViewModel.unitSystem.collect()
        }
        advanceUntilIdle()

        // 1. Initial State verification
        assertEquals(UnitSystem.KILOMETERS, sharedSettingsRepo.preferences.value.unitSystem)
        assertEquals(UnitSystem.KILOMETERS, settingsViewModel.preferences.value.unitSystem)
        assertEquals(UnitSystem.KILOMETERS, routeViewModel.unitSystem.value)
        assertEquals(UnitSystem.KILOMETERS, homeViewModel.unitSystem.value)

        // 2. Mutate to MILES via SettingsViewModel
        settingsViewModel.setUnitSystem(UnitSystem.MILES)
        advanceUntilIdle()

        assertEquals(UnitSystem.MILES, sharedSettingsRepo.preferences.value.unitSystem)
        assertEquals(UnitSystem.MILES, settingsViewModel.preferences.value.unitSystem)
        assertEquals(UnitSystem.MILES, routeViewModel.unitSystem.value)
        assertEquals(UnitSystem.MILES, homeViewModel.unitSystem.value)

        // 3. Mutate back to KILOMETERS via SettingsViewModel
        settingsViewModel.setUnitSystem(UnitSystem.KILOMETERS)
        advanceUntilIdle()

        assertEquals(UnitSystem.KILOMETERS, sharedSettingsRepo.preferences.value.unitSystem)
        assertEquals(UnitSystem.KILOMETERS, settingsViewModel.preferences.value.unitSystem)
        assertEquals(UnitSystem.KILOMETERS, routeViewModel.unitSystem.value)
        assertEquals(UnitSystem.KILOMETERS, homeViewModel.unitSystem.value)
    }

    /**
     * TEST 2: Changing UnitSystem preserves active and generated route state.
     *
     * Verifies that toggling presentation units (METRIC <-> IMPERIAL) alters only display
     * formatting and does NOT regenerate, clear, invalidate, or alter:
     * - RouteViewModel.selectedRoute
     * - HomeViewModel.activeRoute (satisfying the Issue #7A activeRoute === selectedRoute invariant)
     * - RouteViewModel.generatedRoute (including serialized bytes and CRC)
     * - RouteViewModel.isSendRouteEnabled
     */
    @Test
    fun testUnitSystemToggle_PreservesActiveAndGeneratedRouteState() = runTest(testDispatcher) {
        val sharedSettingsRepo = InMemorySettingsRepository()
        sharedSettingsRepo.setUnitSystem(UnitSystem.KILOMETERS)

        val settingsViewModel = SettingsViewModel(settingsRepository = sharedSettingsRepo)
        val fakeValhallaRepo = FakeValhallaRouteRepository()
        val routeViewModel = RouteViewModel(
            routeRepository = SampleRouteRepository.instance,
            bleRepository = FakeBleRepository(),
            settingsRepository = sharedSettingsRepo,
            valhallaRouteRepository = fakeValhallaRepo,
            locationSearchRepository = FakeLocationSearchRepository()
        )
        val homeViewModel = HomeViewModel(
            bleRepository = FakeBleRepository(),
            activeRoute = routeViewModel.selectedRoute,
            settingsRepository = sharedSettingsRepo
        )

        // Activate WhileSubscribed(5000) sharing
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            routeViewModel.unitSystem.collect()
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            homeViewModel.unitSystem.collect()
        }
        advanceUntilIdle()

        // Generate a route
        fakeValhallaRepo.resultToReturn = Result.success(createValidConversionResult())
        val origin = RoutePoint(latitude = 15.35, longitude = 75.1491, name = "Hubballi")
        val destination = RoutePoint(latitude = 15.44, longitude = 75.0045, name = "Dharwad")
        routeViewModel.selectStartLocation(origin)
        routeViewModel.selectDestination(destination)
        routeViewModel.generateRoute()
        advanceUntilIdle()

        // Verify route generation completed successfully
        val initialSelectedRoute = routeViewModel.selectedRoute.value
        val initialGeneratedState = routeViewModel.generatedRoute.value
        assertNotNull("Generated route state must be non-null", initialGeneratedState)
        requireNotNull(initialGeneratedState)
        assertTrue("Send route must be enabled", routeViewModel.isSendRouteEnabled.value)

        // Issue #7A invariant check: HomeViewModel activeRoute is the exact same StateFlow
        assertSame(
            "Issue #7A invariant: homeViewModel.activeRoute must refer to routeViewModel.selectedRoute",
            routeViewModel.selectedRoute,
            homeViewModel.activeRoute
        )
        assertSame(
            "Active route instance must match initial selected route",
            initialSelectedRoute,
            homeViewModel.activeRoute.value
        )

        val initialBinary = initialGeneratedState.serializedBinary.copyOf()
        val initialCrc = initialGeneratedState.crc32

        // Toggle to MILES
        settingsViewModel.setUnitSystem(UnitSystem.MILES)
        advanceUntilIdle()

        // Verify state preservation under MILES
        assertEquals(UnitSystem.MILES, routeViewModel.unitSystem.value)
        assertEquals(UnitSystem.MILES, homeViewModel.unitSystem.value)

        assertSame(
            "selectedRoute instance must remain unchanged after switching to MILES",
            initialSelectedRoute,
            routeViewModel.selectedRoute.value
        )
        assertSame(
            "homeViewModel.activeRoute must still reference routeViewModel.selectedRoute after switching to MILES",
            routeViewModel.selectedRoute.value,
            homeViewModel.activeRoute.value
        )
        assertSame(
            "generatedRoute instance must remain unchanged after switching to MILES",
            initialGeneratedState,
            routeViewModel.generatedRoute.value
        )
        assertArrayEquals(
            "Serialized binary must remain unchanged after switching to MILES",
            initialBinary,
            routeViewModel.generatedRoute.value?.serializedBinary
        )
        assertEquals(
            "CRC32 must remain unchanged after switching to MILES",
            initialCrc,
            routeViewModel.generatedRoute.value?.crc32
        )
        assertTrue(
            "isSendRouteEnabled must remain true after switching to MILES",
            routeViewModel.isSendRouteEnabled.value
        )

        // Toggle back to KILOMETERS
        settingsViewModel.setUnitSystem(UnitSystem.KILOMETERS)
        advanceUntilIdle()

        // Verify state preservation under KILOMETERS
        assertEquals(UnitSystem.KILOMETERS, routeViewModel.unitSystem.value)
        assertEquals(UnitSystem.KILOMETERS, homeViewModel.unitSystem.value)

        assertSame(
            "selectedRoute instance must remain unchanged after switching back to KILOMETERS",
            initialSelectedRoute,
            routeViewModel.selectedRoute.value
        )
        assertSame(
            "homeViewModel.activeRoute must still reference routeViewModel.selectedRoute after switching back to KILOMETERS",
            routeViewModel.selectedRoute.value,
            homeViewModel.activeRoute.value
        )
        assertSame(
            "generatedRoute instance must remain unchanged after switching back to KILOMETERS",
            initialGeneratedState,
            routeViewModel.generatedRoute.value
        )
        assertArrayEquals(
            "Serialized binary must remain unchanged after switching back to KILOMETERS",
            initialBinary,
            routeViewModel.generatedRoute.value?.serializedBinary
        )
        assertEquals(
            "CRC32 must remain unchanged after switching back to KILOMETERS",
            initialCrc,
            routeViewModel.generatedRoute.value?.crc32
        )
        assertTrue(
            "isSendRouteEnabled must remain true after switching back to KILOMETERS",
            routeViewModel.isSendRouteEnabled.value
        )
    }

    /**
     * TEST 3: Default navigation wiring shares the InMemorySettingsRepository singleton.
     *
     * Inspects observable singleton behavior:
     * - The default constructors for SettingsViewModel, RouteViewModel, and HomeViewModel
     *   all wire to InMemorySettingsRepository.instance.
     * - Mutating UnitSystem via the default SettingsViewModel updates
     *   InMemorySettingsRepository.instance directly and propagates to default RouteViewModel
     *   and HomeViewModel.
     * - Cleans up by restoring KILOMETERS in a finally block to prevent test pollution.
     */
    @Test
    fun testDefaultNavigationWiring_SharesSingletonSettingsRepository() = runTest(testDispatcher) {
        val singletonRepo = InMemorySettingsRepository.instance

        try {
            // Explicitly establish known starting state
            singletonRepo.setUnitSystem(UnitSystem.KILOMETERS)

            // Instantiate ViewModels using production default constructors
            val defaultSettingsViewModel = SettingsViewModel()
            val defaultRouteViewModel = RouteViewModel()
            val defaultHomeViewModel = HomeViewModel(
                activeRoute = defaultRouteViewModel.selectedRoute
            )

            // Activate WhileSubscribed(5000) sharing
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                defaultRouteViewModel.unitSystem.collect()
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                defaultHomeViewModel.unitSystem.collect()
            }
            advanceUntilIdle()

            assertEquals(
                "Default SettingsViewModel must observe singleton repository state",
                UnitSystem.KILOMETERS,
                defaultSettingsViewModel.preferences.value.unitSystem
            )
            assertEquals(
                "Default RouteViewModel must observe singleton repository state",
                UnitSystem.KILOMETERS,
                defaultRouteViewModel.unitSystem.value
            )
            assertEquals(
                "Default HomeViewModel must observe singleton repository state",
                UnitSystem.KILOMETERS,
                defaultHomeViewModel.unitSystem.value
            )

            // Mutate via default SettingsViewModel
            defaultSettingsViewModel.setUnitSystem(UnitSystem.MILES)
            advanceUntilIdle()

            // Verify the singleton repository was updated directly
            assertEquals(
                "Singleton repository must observe change dispatched by default SettingsViewModel",
                UnitSystem.MILES,
                singletonRepo.preferences.value.unitSystem
            )
            assertEquals(
                "Default RouteViewModel must observe update from singleton repository",
                UnitSystem.MILES,
                defaultRouteViewModel.unitSystem.value
            )
            assertEquals(
                "Default HomeViewModel must observe update from singleton repository",
                UnitSystem.MILES,
                defaultHomeViewModel.unitSystem.value
            )
        } finally {
            // Ensure test isolation by restoring default KILOMETERS on the singleton
            singletonRepo.setUnitSystem(UnitSystem.KILOMETERS)
        }
    }
}
