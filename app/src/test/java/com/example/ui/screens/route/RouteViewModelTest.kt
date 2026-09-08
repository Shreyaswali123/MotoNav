package com.example.ui.screens.route

import com.example.ble.BleRepository
import com.example.data.SampleRouteRepository
import com.example.model.BleDiagnostics
import com.example.model.ConnectionState
import com.example.model.MotoNavDevice
import com.example.model.Route
import com.example.model.RoutePoint
import com.example.model.RouteTransferProgress
import com.example.network.ValhallaRouteRepository
import com.example.route.RouteConversionResult
import com.example.route.SerializedMotoNavRoute
import com.example.settings.InMemorySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RouteViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeBleRepository : BleRepository {
        override val connectionState = MutableStateFlow(ConnectionState.Connected)
        override val connectedDevice = MutableStateFlow<MotoNavDevice?>(null)
        override val diagnostics = MutableStateFlow(BleDiagnostics())
        override val transferProgress = MutableStateFlow(RouteTransferProgress())
        override val lastError = MutableStateFlow<String?>(null)

        var sendTestRouteCalled = false
        var transferRouteCalled = false
        var transferSerializedRouteCalled = false
        var lastTransferredBinary: ByteArray? = null
        var lastTransferredCrc32: Long? = null

        override fun connect(deviceId: String) {}
        override fun disconnect() {}
        override fun readStatus() {}

        override fun sendTestRoute() {
            sendTestRouteCalled = true
        }

        override fun transferRoute(route: Route) {
            transferRouteCalled = true
        }

        override fun transferSerializedRoute(binary: ByteArray, crc32: Long) {
            transferSerializedRouteCalled = true
            lastTransferredBinary = binary
            lastTransferredCrc32 = crc32
        }

        override fun cancelTransfer() {}
        override fun resetError() {}
        override fun simulateError() {}
    }

    private class FakeValhallaRouteRepository : ValhallaRouteRepository() {
        var fetchRouteCalled = false
        var lastOrigin: RoutePoint? = null
        var lastDestination: RoutePoint? = null
        var lastRouteId: Long? = null
        var resultToReturn: Result<RouteConversionResult>? = null

        override suspend fun fetchRoute(
            origin: RoutePoint,
            destination: RoutePoint,
            routeId: Long
        ): Result<RouteConversionResult> {
            fetchRouteCalled = true
            lastOrigin = origin
            lastDestination = destination
            lastRouteId = routeId
            return resultToReturn ?: Result.failure(IllegalStateException("No fake result set"))
        }
    }

    private lateinit var fakeBleRepo: FakeBleRepository
    private lateinit var fakeValhallaRepo: FakeValhallaRouteRepository
    private lateinit var sampleRouteRepo: SampleRouteRepository
    private lateinit var settingsRepo: InMemorySettingsRepository
    private lateinit var viewModel: RouteViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeBleRepo = FakeBleRepository()
        fakeValhallaRepo = FakeValhallaRouteRepository()
        sampleRouteRepo = SampleRouteRepository()
        settingsRepo = InMemorySettingsRepository()

        viewModel = RouteViewModel(
            routeRepository = sampleRouteRepo,
            bleRepository = fakeBleRepo,
            settingsRepository = settingsRepo,
            valhallaRouteRepository = fakeValhallaRepo
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createFakeConversionResult(
        binary: ByteArray = byteArrayOf(0x4D, 0x4E, 0x56, 0x31, 0x01, 0x02, 0x03),
        crc32: Long = 0xA1B2C3D4L
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
            totalDistanceMeters = 42800,
            durationSeconds = 3120
        )
    }

    @Test
    fun testA_SelectedRouteCausesValhallaFetchRouteInvocation() = runTest(testDispatcher) {
        val selected = viewModel.selectedRoute.value
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertTrue("ValhallaRouteRepository.fetchRoute() must be invoked", fakeValhallaRepo.fetchRouteCalled)
        assertEquals("Origin must match selected route startLocation", selected.startLocation, fakeValhallaRepo.lastOrigin)
        assertEquals("Destination must match selected route destination", selected.destination, fakeValhallaRepo.lastDestination)
    }

    @Test
    fun testB_SuccessfulConversionCausesTransferSerializedRouteWithBinaryAndCrc() = runTest(testDispatcher) {
        val expectedBinary = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val expectedCrc = 0x98765432L
        val fakeResult = createFakeConversionResult(binary = expectedBinary, crc32 = expectedCrc)
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertTrue("bleRepository.transferSerializedRoute must be called", fakeBleRepo.transferSerializedRouteCalled)
        assertArrayEquals("Binary passed to transferSerializedRoute must match conversion output", expectedBinary, fakeBleRepo.lastTransferredBinary)
        assertEquals("CRC32 passed to transferSerializedRoute must match conversion output", expectedCrc, fakeBleRepo.lastTransferredCrc32)
        assertEquals("Route generation state must be Success", RouteGenerationState.Success, viewModel.routeGenerationState.value)
        assertFalse("isGeneratingRoute must be false after completion", viewModel.isGeneratingRoute.value)
        assertNull("routeGenerationError must be null on success", viewModel.routeGenerationError.value)
    }

    @Test
    fun testC_ProductionSendPathDoesNotCallSendTestRouteOrTransferRoute() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertFalse("Production send path must NOT call sendTestRoute()", fakeBleRepo.sendTestRouteCalled)
        assertFalse("Production send path must NOT call legacy transferRoute()", fakeBleRepo.transferRouteCalled)
    }

    @Test
    fun testD_ValhallaFailureDoesNotTriggerBleTransfer() = runTest(testDispatcher) {
        val networkErrorMsg = "Valhalla server timed out or connection refused"
        fakeValhallaRepo.resultToReturn = Result.failure(IOException(networkErrorMsg))

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertFalse("BLE transferSerializedRoute must NOT be called on Valhalla failure", fakeBleRepo.transferSerializedRouteCalled)
        assertFalse("sendTestRoute must NOT be called on Valhalla failure", fakeBleRepo.sendTestRouteCalled)
        assertFalse("transferRoute must NOT be called on Valhalla failure", fakeBleRepo.transferRouteCalled)

        assertEquals("Route generation error message must be exposed", networkErrorMsg, viewModel.routeGenerationError.value)
        assertTrue("Route generation state must be RouteGenerationError", viewModel.routeGenerationState.value is RouteGenerationState.RouteGenerationError)
        assertEquals("Route generation error state message must match", networkErrorMsg, (viewModel.routeGenerationState.value as RouteGenerationState.RouteGenerationError).message)
        assertFalse("isGeneratingRoute must be false after failure", viewModel.isGeneratingRoute.value)
    }

    @Test
    fun testConversionFailureDoesNotTriggerBleTransfer() = runTest(testDispatcher) {
        val conversionErrorMsg = "Unsupported maneuver structure during conversion"
        fakeValhallaRepo.resultToReturn = Result.failure(IllegalArgumentException(conversionErrorMsg))

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertFalse("BLE transferSerializedRoute must NOT be called on conversion failure", fakeBleRepo.transferSerializedRouteCalled)
        assertFalse("sendTestRoute must NOT be called on conversion failure", fakeBleRepo.sendTestRouteCalled)
        assertEquals("Conversion error must be exposed", conversionErrorMsg, viewModel.routeGenerationError.value)
        assertFalse("isGeneratingRoute must be false", viewModel.isGeneratingRoute.value)
    }

    @Test
    fun testSelectingDifferentRoutePassesUpdatedCoordinatesToValhalla() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.selectRoute("route_coastal_highway")
        advanceUntilIdle()

        val selected = viewModel.selectedRoute.value
        assertEquals("Selected route ID should be route_coastal_highway", "route_coastal_highway", selected.id)

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertTrue("ValhallaRouteRepository.fetchRoute() must be invoked", fakeValhallaRepo.fetchRouteCalled)
        assertEquals("Origin must match the newly selected route", selected.startLocation, fakeValhallaRepo.lastOrigin)
        assertEquals("Destination must match the newly selected route", selected.destination, fakeValhallaRepo.lastDestination)
    }

    @Test
    fun testInitialStartAndDestinationArePopulatedFromSelectedRoute() = runTest(testDispatcher) {
        advanceUntilIdle()
        val selected = viewModel.selectedRoute.value
        assertEquals("startLocation should match selected route start", selected.startLocation, viewModel.startLocation.value)
        assertEquals("destination should match selected route destination", selected.destination, viewModel.destination.value)
        assertTrue("Send route should be enabled initially with valid endpoints", viewModel.isSendRouteEnabled.value)
        assertNull("Validation error should be null initially", viewModel.locationValidationError.value)
    }

    @Test
    fun testSelectStartAndDestinationWithHubballiLocations() = runTest(testDispatcher) {
        val kleTech = RoutePoint(15.3647, 75.1240, name = "KLE Technological University, Hubballi")
        val tolanKere = RoutePoint(15.3610, 75.0863, name = "TolanKere, Hubballi")

        viewModel.selectStartLocation(kleTech)
        viewModel.selectDestination(tolanKere)
        advanceUntilIdle()

        assertEquals("KLE Technological University, Hubballi", viewModel.startLocation.value?.name)
        assertEquals(15.3647, viewModel.startLocation.value?.latitude ?: 0.0, 0.0001)
        assertEquals(75.1240, viewModel.startLocation.value?.longitude ?: 0.0, 0.0001)

        assertEquals("TolanKere, Hubballi", viewModel.destination.value?.name)
        assertEquals(15.3610, viewModel.destination.value?.latitude ?: 0.0, 0.0001)
        assertEquals(75.0863, viewModel.destination.value?.longitude ?: 0.0, 0.0001)

        assertTrue("isSendRouteEnabled must be true when distinct valid endpoints are selected", viewModel.isSendRouteEnabled.value)
        assertNull(viewModel.locationValidationError.value)
    }

    @Test
    fun testSameStartAndDestinationDisablesSendRoute() = runTest(testDispatcher) {
        val kleTech = RoutePoint(15.3647, 75.1240, name = "KLE Technological University, Hubballi")

        viewModel.selectStartLocation(kleTech)
        viewModel.selectDestination(kleTech)
        advanceUntilIdle()

        assertFalse("isSendRouteEnabled must be false when start and end are identical", viewModel.isSendRouteEnabled.value)
        assertEquals("Start and destination cannot be the same location", viewModel.locationValidationError.value)
    }

    @Test
    fun testClearingStartLocationDisablesSendRoute() = runTest(testDispatcher) {
        viewModel.clearStartLocation()
        advanceUntilIdle()

        assertNull("startLocation should be null after clearing", viewModel.startLocation.value)
        assertFalse("isSendRouteEnabled must be false when startLocation is null", viewModel.isSendRouteEnabled.value)
    }

    @Test
    fun testClearingDestinationDisablesSendRoute() = runTest(testDispatcher) {
        viewModel.clearDestination()
        advanceUntilIdle()

        assertNull("destination should be null after clearing", viewModel.destination.value)
        assertFalse("isSendRouteEnabled must be false when destination is null", viewModel.isSendRouteEnabled.value)
    }

    @Test
    fun testSwapLocationsInvertsStartAndDestination() = runTest(testDispatcher) {
        val origin = RoutePoint(15.3647, 75.1240, name = "KLE Technological University, Hubballi")
        val destination = RoutePoint(15.3610, 75.0863, name = "TolanKere, Hubballi")

        viewModel.selectStartLocation(origin)
        viewModel.selectDestination(destination)
        advanceUntilIdle()

        viewModel.swapLocations()
        advanceUntilIdle()

        assertEquals("TolanKere, Hubballi", viewModel.startLocation.value?.name)
        assertEquals("KLE Technological University, Hubballi", viewModel.destination.value?.name)
        assertTrue(viewModel.isSendRouteEnabled.value)
    }

    @Test
    fun testLocationSearchFindsKLETechAndTolanKere() = runTest(testDispatcher) {
        // Search for "KLE"
        viewModel.searchLocations("KLE")
        advanceUntilIdle()

        val kleResults = viewModel.locationSearchResults.value
        assertTrue("Search for 'KLE' should return results", kleResults.isNotEmpty())
        assertTrue(
            "Results should contain KLE Tech",
            kleResults.any { it.name.contains("KLE", ignoreCase = true) }
        )

        // Search for "TolanKere"
        viewModel.searchLocations("TolanKere")
        advanceUntilIdle()

        val tolanResults = viewModel.locationSearchResults.value
        assertTrue("Search for 'TolanKere' should return results", tolanResults.isNotEmpty())
        assertTrue(
            "Results should contain TolanKere",
            tolanResults.any { it.name.contains("TolanKere", ignoreCase = true) }
        )
    }

    @Test
    fun testCustomEndpointsSentToValhallaRouteRepository() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        val kleTech = RoutePoint(15.3647, 75.1240, name = "KLE Technological University, Hubballi")
        val tolanKere = RoutePoint(15.3610, 75.0863, name = "TolanKere, Hubballi")

        viewModel.selectStartLocation(kleTech)
        viewModel.selectDestination(tolanKere)
        advanceUntilIdle()

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertTrue("ValhallaRouteRepository.fetchRoute() must be invoked", fakeValhallaRepo.fetchRouteCalled)
        assertEquals("Origin sent to Valhalla must match selected startLocation", kleTech, fakeValhallaRepo.lastOrigin)
        assertEquals("Destination sent to Valhalla must match selected destination", tolanKere, fakeValhallaRepo.lastDestination)
        assertTrue("BLE transferSerializedRoute must be called", fakeBleRepo.transferSerializedRouteCalled)
    }
}
