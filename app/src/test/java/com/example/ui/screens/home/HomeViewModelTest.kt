package com.example.ui.screens.home

import com.example.ble.BleRepository
import com.example.data.LocationSearchRepository
import com.example.data.SampleRouteRepository
import com.example.data.SearchLocation
import com.example.model.BleDiagnostics
import com.example.model.ConnectionState
import com.example.model.MotoNavDevice
import com.example.model.RoutePoint
import com.example.model.RouteTransferProgress
import com.example.network.ValhallaRouteRepository
import com.example.route.RouteConversionResult
import com.example.route.SerializedMotoNavRoute
import com.example.settings.InMemorySettingsRepository
import com.example.ui.screens.route.RouteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

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

    private lateinit var fakeBleRepo: FakeBleRepository
    private lateinit var fakeValhallaRepo: FakeValhallaRouteRepository
    private lateinit var routeViewModel: RouteViewModel
    private lateinit var homeViewModel: HomeViewModel

    private fun createFakeConversionResult(
        binary: ByteArray = byteArrayOf(0x4D, 0x4E, 0x56, 0x31, 0x01, 0x02, 0x03),
        crc32: Long = 0xA1B2C3D4L,
        totalDistanceMeters: Int = 42800,
        durationSeconds: Int = 3120
    ): RouteConversionResult {
        return RouteConversionResult(
            serializedRoute = SerializedMotoNavRoute(
                binary = binary,
                crc32 = crc32
            ),
            decodedPoints = listOf(
                RoutePoint(37.7749, -122.4194),
                RoutePoint(37.8920, -122.5650)
            ),
            simplifiedPoints = listOf(
                RoutePoint(37.7749, -122.4194),
                RoutePoint(37.8920, -122.5650)
            ),
            maneuvers = emptyList(),
            totalDistanceMeters = totalDistanceMeters,
            durationSeconds = durationSeconds
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeBleRepo = FakeBleRepository()
        fakeValhallaRepo = FakeValhallaRouteRepository()

        routeViewModel = RouteViewModel(
            bleRepository = fakeBleRepo,
            routeRepository = SampleRouteRepository(),
            settingsRepository = InMemorySettingsRepository.instance,
            locationSearchRepository = FakeLocationSearchRepository(),
            valhallaRouteRepository = fakeValhallaRepo,
            initialStartLocation = RoutePoint(37.7749, -122.4194, name = "San Francisco"),
            initialDestination = RoutePoint(37.8920, -122.5650, name = "Mount Tamalpais")
        )

        homeViewModel = HomeViewModel(
            bleRepository = fakeBleRepo,
            activeRoute = routeViewModel.selectedRoute,
            settingsRepository = InMemorySettingsRepository.instance
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun test1_GeneratedRouteBecomesVisibleThroughHomeFacingState() = runTest(testDispatcher) {
        // A. Before generation: Home shows initial preset route
        val initialHomeRoute = homeViewModel.activeRoute.value
        assertEquals("Bear Peak Mountain Pass", initialHomeRoute.title)

        // B. Generate custom route
        fakeValhallaRepo.resultToReturn = Result.success(
            createFakeConversionResult(totalDistanceMeters = 42800, durationSeconds = 3120)
        )
        routeViewModel.generateRoute()
        advanceUntilIdle()

        // Home must now observe the generated route
        val updatedHomeRoute = homeViewModel.activeRoute.value
        assertEquals(42800, updatedHomeRoute.totalDistanceMeters)
        assertEquals(3120, updatedHomeRoute.estimatedDurationSeconds)
        assertEquals(2, updatedHomeRoute.waypoints.size)
        assertEquals(routeViewModel.selectedRoute.value, updatedHomeRoute)
    }

    @Test
    fun test2_HomeFacingStateReflectsSameRouteAsRouteViewModel() = runTest(testDispatcher) {
        // Single authoritative source of truth: the StateFlow instance itself is identical
        assertSame(
            "homeViewModel.activeRoute must be the exact same StateFlow as routeViewModel.selectedRoute",
            routeViewModel.selectedRoute,
            homeViewModel.activeRoute
        )

        fakeValhallaRepo.resultToReturn = Result.success(createFakeConversionResult())
        routeViewModel.generateRoute()
        advanceUntilIdle()

        assertEquals(
            "Home route value must match RouteViewModel selectedRoute value exactly",
            routeViewModel.selectedRoute.value,
            homeViewModel.activeRoute.value
        )
    }

    @Test
    fun test3_ChangingOriginInvalidatesActiveGeneratedRoute() = runTest(testDispatcher) {
        // First generate a valid route
        fakeValhallaRepo.resultToReturn = Result.success(createFakeConversionResult())
        routeViewModel.generateRoute()
        advanceUntilIdle()

        assertTrue(homeViewModel.activeRoute.value.waypoints.isNotEmpty())

        // Change origin
        val newOrigin = RoutePoint(37.8000, -122.4000, name = "Fisherman's Wharf")
        routeViewModel.selectStartLocation(newOrigin)

        // RouteViewModel generated route must be invalidated
        assertNull(routeViewModel.generatedRoute.value)

        // Home active route must not retain stale generated route data
        val homeRoute = homeViewModel.activeRoute.value
        assertTrue("Waypoints must be empty after invalidation", homeRoute.waypoints.isEmpty())
        assertEquals("Distance must be 0 after invalidation", 0, homeRoute.totalDistanceMeters)
        assertEquals("Duration must be 0 after invalidation", 0, homeRoute.estimatedDurationSeconds)
        assertEquals("Fisherman's Wharf", homeRoute.startLocation.name)
    }

    @Test
    fun test4_ChangingDestinationInvalidatesActiveGeneratedRoute() = runTest(testDispatcher) {
        // First generate a valid route
        fakeValhallaRepo.resultToReturn = Result.success(createFakeConversionResult())
        routeViewModel.generateRoute()
        advanceUntilIdle()

        assertTrue(homeViewModel.activeRoute.value.waypoints.isNotEmpty())

        // Change destination
        val newDest = RoutePoint(37.8500, -122.5000, name = "Marin Headlands")
        routeViewModel.selectDestination(newDest)

        // RouteViewModel generated route must be invalidated
        assertNull(routeViewModel.generatedRoute.value)

        // Home active route must not retain stale generated route data
        val homeRoute = homeViewModel.activeRoute.value
        assertTrue("Waypoints must be empty after invalidation", homeRoute.waypoints.isEmpty())
        assertEquals("Distance must be 0 after invalidation", 0, homeRoute.totalDistanceMeters)
        assertEquals("Duration must be 0 after invalidation", 0, homeRoute.estimatedDurationSeconds)
        assertEquals("Marin Headlands", homeRoute.destination.name)
    }

    @Test
    fun test5_FailedRouteGenerationDoesNotExposeStaleGeneratedRouteState() = runTest(testDispatcher) {
        // First generate a valid route
        fakeValhallaRepo.resultToReturn = Result.success(createFakeConversionResult())
        routeViewModel.generateRoute()
        advanceUntilIdle()

        assertTrue(homeViewModel.activeRoute.value.waypoints.isNotEmpty())

        // Now attempt a generation that fails
        fakeValhallaRepo.resultToReturn = Result.failure(IOException("Valhalla server unreachable"))
        routeViewModel.generateRoute()
        advanceUntilIdle()

        // Neither RouteViewModel nor Home should retain stale generated route data
        assertNull("Generated route state must be null on failure", routeViewModel.generatedRoute.value)
        val homeRoute = homeViewModel.activeRoute.value
        assertTrue("Waypoints must be cleared on failure", homeRoute.waypoints.isEmpty())
        assertEquals("Total distance must be 0 on failure", 0, homeRoute.totalDistanceMeters)
        assertEquals("Duration must be 0 on failure", 0, homeRoute.estimatedDurationSeconds)
    }

    @Test
    fun test6_NavigatingBetweenHomeAndRoutePlanningDoesNotCreateSecondRouteStateInstance() = runTest(testDispatcher) {
        // Verify provideFactory wires the exact same StateFlow
        val factory = HomeViewModel.provideFactory(
            bleRepository = fakeBleRepo,
            activeRoute = routeViewModel.selectedRoute,
            settingsRepository = InMemorySettingsRepository.instance
        )
        val navigatedHomeVm = factory.create(HomeViewModel::class.java)

        assertSame(
            "Factory-created HomeViewModel must share the exact StateFlow reference with RouteViewModel",
            routeViewModel.selectedRoute,
            navigatedHomeVm.activeRoute
        )

        // When a route is generated on RoutePlanningScreen
        fakeValhallaRepo.resultToReturn = Result.success(createFakeConversionResult())
        routeViewModel.generateRoute()
        advanceUntilIdle()

        // The route is immediately consistent on Home without recreation or syncing
        assertEquals(
            routeViewModel.selectedRoute.value,
            navigatedHomeVm.activeRoute.value
        )
    }
}
