package com.example.ui.screens.route

import com.example.ble.BleRepository
import com.example.data.LocationSearchRepository
import com.example.data.LocationSearchResult
import com.example.data.SampleRouteRepository
import com.example.data.SearchLocation
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        var transferSerializedRouteCallCount = 0
        val transferSerializedRouteCalled: Boolean get() = transferSerializedRouteCallCount > 0
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
            transferSerializedRouteCallCount++
            lastTransferredBinary = binary
            lastTransferredCrc32 = crc32
        }

        override fun cancelTransfer() {}
        override fun resetError() {
            lastError.value = null
        }
        override fun simulateError() {
            connectionState.value = ConnectionState.Error
            lastError.value = "Simulated BLE error"
        }
    }

    private class FakeValhallaRouteRepository : ValhallaRouteRepository() {
        var fetchRouteCallCount = 0
        val fetchRouteCalled: Boolean get() = fetchRouteCallCount > 0
        var lastOrigin: RoutePoint? = null
        var lastDestination: RoutePoint? = null
        var lastRouteId: Long? = null
        var resultToReturn: Result<RouteConversionResult>? = null

        override suspend fun fetchRoute(
            origin: RoutePoint,
            destination: RoutePoint,
            routeId: Long
        ): Result<RouteConversionResult> {
            fetchRouteCallCount++
            lastOrigin = origin
            lastDestination = destination
            lastRouteId = routeId
            return resultToReturn ?: Result.failure(IllegalStateException("No fake result set"))
        }
    }

    private class FakeLocationSearchRepository : LocationSearchRepository {
        var popularList: List<SearchLocation> = listOf(
            SearchLocation("loc_kle", "KLE Technological University, Hubballi", "Vidyanagar, Hubballi", 15.3690, 75.1236),
            SearchLocation("loc_tolan", "TolanKere, Hubballi", "TolanKere Lake, Hubballi", 15.3520, 75.1380)
        )
        var searchHandler: (String) -> LocationSearchResult = { q ->
            val matches = popularList.filter { it.name.contains(q, ignoreCase = true) }
            if (matches.isNotEmpty()) LocationSearchResult.Success(matches)
            else LocationSearchResult.Empty(q)
        }
        var searchCallCount = 0
        var lastQuery: String? = null

        override suspend fun search(query: String): LocationSearchResult {
            searchCallCount++
            lastQuery = query
            return searchHandler(query)
        }

        override suspend fun searchLocations(query: String): List<SearchLocation> {
            return when (val res = search(query)) {
                is LocationSearchResult.Success -> res.locations
                else -> emptyList()
            }
        }

        override fun getPopularLocations(): List<SearchLocation> = popularList
    }

    private lateinit var fakeBleRepo: FakeBleRepository
    private lateinit var fakeValhallaRepo: FakeValhallaRouteRepository
    private lateinit var fakeLocationRepo: FakeLocationSearchRepository
    private lateinit var sampleRouteRepo: SampleRouteRepository
    private lateinit var settingsRepo: InMemorySettingsRepository
    private lateinit var viewModel: RouteViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeBleRepo = FakeBleRepository()
        fakeValhallaRepo = FakeValhallaRouteRepository()
        fakeLocationRepo = FakeLocationSearchRepository()
        sampleRouteRepo = SampleRouteRepository()
        settingsRepo = InMemorySettingsRepository()

        viewModel = RouteViewModel(
            routeRepository = sampleRouteRepo,
            bleRepository = fakeBleRepo,
            settingsRepository = settingsRepo,
            valhallaRouteRepository = fakeValhallaRepo,
            locationSearchRepository = fakeLocationRepo
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

        viewModel.generateRoute()
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

        viewModel.generateRoute()
        advanceUntilIdle()

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

        viewModel.generateRoute()
        advanceUntilIdle()

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertFalse("Production send path must NOT call sendTestRoute()", fakeBleRepo.sendTestRouteCalled)
        assertFalse("Production send path must NOT call legacy transferRoute()", fakeBleRepo.transferRouteCalled)
    }

    @Test
    fun testD_ValhallaFailureDoesNotTriggerBleTransfer() = runTest(testDispatcher) {
        val networkErrorMsg = "Valhalla server timed out or connection refused"
        fakeValhallaRepo.resultToReturn = Result.failure(IOException(networkErrorMsg))

        viewModel.generateRoute()
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

        viewModel.generateRoute()
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

        viewModel.generateRoute()
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
        assertTrue("Generate route should be enabled initially with valid endpoints", viewModel.isGenerateRouteEnabled.value)
        assertFalse("Send route must be disabled initially before generation", viewModel.isSendRouteEnabled.value)
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

        assertTrue("isGenerateRouteEnabled must be true when distinct valid endpoints are selected", viewModel.isGenerateRouteEnabled.value)
        assertFalse("isSendRouteEnabled must be false until route is generated", viewModel.isSendRouteEnabled.value)
        assertNull(viewModel.locationValidationError.value)
    }

    @Test
    fun testSameStartAndDestinationDisablesSendRoute() = runTest(testDispatcher) {
        val kleTech = RoutePoint(15.3647, 75.1240, name = "KLE Technological University, Hubballi")

        viewModel.selectStartLocation(kleTech)
        viewModel.selectDestination(kleTech)
        advanceUntilIdle()

        assertFalse("isGenerateRouteEnabled must be false when start and end are identical", viewModel.isGenerateRouteEnabled.value)
        assertFalse("isSendRouteEnabled must be false when start and end are identical", viewModel.isSendRouteEnabled.value)
        assertEquals("Start and destination cannot be the same location", viewModel.locationValidationError.value)
    }

    @Test
    fun testClearingStartLocationDisablesSendRoute() = runTest(testDispatcher) {
        viewModel.clearStartLocation()
        advanceUntilIdle()

        assertNull("startLocation should be null after clearing", viewModel.startLocation.value)
        assertFalse("isGenerateRouteEnabled must be false when startLocation is null", viewModel.isGenerateRouteEnabled.value)
        assertFalse("isSendRouteEnabled must be false when startLocation is null", viewModel.isSendRouteEnabled.value)
    }

    @Test
    fun testClearingDestinationDisablesSendRoute() = runTest(testDispatcher) {
        viewModel.clearDestination()
        advanceUntilIdle()

        assertNull("destination should be null after clearing", viewModel.destination.value)
        assertFalse("isGenerateRouteEnabled must be false when destination is null", viewModel.isGenerateRouteEnabled.value)
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
        assertTrue(viewModel.isGenerateRouteEnabled.value)
        assertFalse(viewModel.isSendRouteEnabled.value)
    }

    @Test
    fun testLocationSearchFindsKLETechAndTolanKere() = runTest(testDispatcher) {
        viewModel.searchLocations("KLE")
        advanceUntilIdle()

        val kleResults = viewModel.locationSearchResults.value
        assertTrue("Search for 'KLE' should return results", kleResults.isNotEmpty())
        assertTrue(
            "Results should contain KLE Tech",
            kleResults.any { it.name.contains("KLE", ignoreCase = true) }
        )

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

        viewModel.generateRoute()
        advanceUntilIdle()

        assertTrue("ValhallaRouteRepository.fetchRoute() must be invoked", fakeValhallaRepo.fetchRouteCalled)
        assertEquals("Origin sent to Valhalla must match selected startLocation", kleTech, fakeValhallaRepo.lastOrigin)
        assertEquals("Destination sent to Valhalla must match selected destination", tolanKere, fakeValhallaRepo.lastDestination)

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertTrue("BLE transferSerializedRoute must be called", fakeBleRepo.transferSerializedRouteCalled)
    }

    // =========================================================================
    // TASK 4: 20 EXPLICIT TESTS
    // =========================================================================

    @Test
    fun test01_SelectingOriginAndDestinationDoesNotAutomaticallySendBle() = runTest(testDispatcher) {
        val kleTech = RoutePoint(15.3647, 75.1240, name = "KLE Technological University, Hubballi")
        val tolanKere = RoutePoint(15.3610, 75.0863, name = "TolanKere, Hubballi")

        viewModel.selectStartLocation(kleTech)
        viewModel.selectDestination(tolanKere)
        advanceUntilIdle()

        assertFalse("Selecting origin + destination must NOT send BLE", fakeBleRepo.transferSerializedRouteCalled)
        assertFalse("Selecting origin + destination must NOT call Valhalla", fakeValhallaRepo.fetchRouteCalled)
    }

    @Test
    fun test02_GenerateRouteCallsValhallaExactlyOnce() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()

        assertEquals("generateRoute() must call Valhalla exactly once", 1, fakeValhallaRepo.fetchRouteCallCount)
    }

    @Test
    fun test03_SuccessfulGenerateRouteStoresGeneratedRoute() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()

        val generated = viewModel.generatedRoute.value
        org.junit.Assert.assertNotNull("Successful generateRoute() must store generated route in state", generated)
        assertEquals(fakeResult.simplifiedPoints, generated?.route?.waypoints)
        assertEquals(fakeResult.totalDistanceMeters, generated?.route?.totalDistanceMeters)
        assertEquals(fakeResult.durationSeconds, generated?.route?.estimatedDurationSeconds)
    }

    @Test
    fun test04_SuccessfulGenerateRouteStoresSerializedBinary() = runTest(testDispatcher) {
        val expectedBinary = byteArrayOf(0x4D, 0x4E, 0x56, 0x31, 0x01, 0x02, 0x03)
        val fakeResult = createFakeConversionResult(binary = expectedBinary)
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()

        val generated = viewModel.generatedRoute.value
        org.junit.Assert.assertNotNull(generated)
        assertArrayEquals("Successful generateRoute() must store serialized binary", expectedBinary, generated?.serializedBinary)
    }

    @Test
    fun test05_SuccessfulGenerateRouteStoresCrc() = runTest(testDispatcher) {
        val expectedCrc = 0xFEEDBEEFL
        val fakeResult = createFakeConversionResult(crc32 = expectedCrc)
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()

        val generated = viewModel.generatedRoute.value
        org.junit.Assert.assertNotNull(generated)
        assertEquals("Successful generateRoute() must store CRC32", expectedCrc, generated?.crc32)
    }

    @Test
    fun test06_SuccessfulGenerateRouteDoesNotCallBleTransfer() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()

        assertFalse("generateRoute() must NOT call BLE transfer", fakeBleRepo.transferSerializedRouteCalled)
        assertFalse("generateRoute() must NOT call sendTestRoute", fakeBleRepo.sendTestRouteCalled)
        assertFalse("generateRoute() must NOT call transferRoute", fakeBleRepo.transferRouteCalled)
    }

    @Test
    fun test07_SendRouteToMotoNavAfterSuccessfulGenerationCallsBleTransferExactlyOnce() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()
        assertEquals(0, fakeBleRepo.transferSerializedRouteCallCount)

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertEquals("sendRouteToMotoNav() after generation must call BLE transfer exactly once", 1, fakeBleRepo.transferSerializedRouteCallCount)
    }

    @Test
    fun test08_SendRouteToMotoNavDoesNotCallValhallaAgain() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()
        assertEquals("Valhalla called once for generateRoute()", 1, fakeValhallaRepo.fetchRouteCallCount)

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertEquals("sendRouteToMotoNav() must NOT call Valhalla again", 1, fakeValhallaRepo.fetchRouteCallCount)
    }

    @Test
    fun test09_BleReceivesExactlyStoredSerializedBinary() = runTest(testDispatcher) {
        val expectedBinary = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)
        val fakeResult = createFakeConversionResult(binary = expectedBinary)
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertArrayEquals("BLE transfer must receive exact stored serialized binary", expectedBinary, fakeBleRepo.lastTransferredBinary)
    }

    @Test
    fun test10_BleReceivesExactlyStoredCrc() = runTest(testDispatcher) {
        val expectedCrc = 0xCAFEBABE01L
        val fakeResult = createFakeConversionResult(crc32 = expectedCrc)
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertEquals("BLE transfer must receive exact stored CRC", expectedCrc, fakeBleRepo.lastTransferredCrc32)
    }

    @Test
    fun test11_ChangingOriginInvalidatesGeneratedRoute() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()
        org.junit.Assert.assertNotNull(viewModel.generatedRoute.value)

        val newOrigin = RoutePoint(15.3647, 75.1240, name = "KLE Tech")
        viewModel.selectStartLocation(newOrigin)
        advanceUntilIdle()

        assertNull("Changing origin must invalidate generated route", viewModel.generatedRoute.value)
        assertFalse("Send route must be disabled after invalidation", viewModel.isSendRouteEnabled.value)
    }

    @Test
    fun test12_ChangingDestinationInvalidatesGeneratedRoute() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()
        org.junit.Assert.assertNotNull(viewModel.generatedRoute.value)

        val newDest = RoutePoint(15.3610, 75.0863, name = "TolanKere")
        viewModel.selectDestination(newDest)
        advanceUntilIdle()

        assertNull("Changing destination must invalidate generated route", viewModel.generatedRoute.value)
        assertFalse("Send route must be disabled after invalidation", viewModel.isSendRouteEnabled.value)
    }

    @Test
    fun test13_SwappingLocationsInvalidatesGeneratedRoute() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()
        org.junit.Assert.assertNotNull(viewModel.generatedRoute.value)

        viewModel.swapLocations()
        advanceUntilIdle()

        assertNull("Swapping locations must invalidate generated route", viewModel.generatedRoute.value)
        assertFalse("Send route must be disabled after swap", viewModel.isSendRouteEnabled.value)
    }

    @Test
    fun test14_ClearingLocationInvalidatesGeneratedRoute() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()
        org.junit.Assert.assertNotNull(viewModel.generatedRoute.value)

        viewModel.clearStartLocation()
        advanceUntilIdle()

        assertNull("Clearing origin must invalidate generated route", viewModel.generatedRoute.value)
        assertFalse("Send route must be disabled after clearing", viewModel.isSendRouteEnabled.value)
    }

    @Test
    fun test15_SendIsDisabledWhenNoGeneratedRouteExists() = runTest(testDispatcher) {
        // Initially endpoints are populated, but no route has been generated yet
        advanceUntilIdle()

        assertNull("No generated route initially", viewModel.generatedRoute.value)
        assertTrue("Generate route should be enabled initially with valid endpoints", viewModel.isGenerateRouteEnabled.value)
        assertFalse("Send route must be DISABLED when no generated route exists", viewModel.isSendRouteEnabled.value)
    }

    @Test
    fun test16_SendIsDisabledWhenGeneratedRouteBelongsToOldEndpoints() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()
        assertTrue("Send route should be enabled after generation", viewModel.isSendRouteEnabled.value)

        // Select a different destination
        viewModel.selectDestination(RoutePoint(15.5000, 75.2000, name = "New Spot"))
        advanceUntilIdle()

        assertFalse("Send route must be disabled when generated route belongs to old endpoints", viewModel.isSendRouteEnabled.value)
    }

    @Test
    fun test17_ValhallaFailurePreventsBleTransfer() = runTest(testDispatcher) {
        fakeValhallaRepo.resultToReturn = Result.failure(IOException("Connection refused to Valhalla"))

        viewModel.generateRoute()
        advanceUntilIdle()

        assertNull("Generated route must be null on Valhalla failure", viewModel.generatedRoute.value)
        assertFalse("BLE transfer must NOT be called on Valhalla failure", fakeBleRepo.transferSerializedRouteCalled)
        assertFalse("Send route must remain disabled", viewModel.isSendRouteEnabled.value)
        assertEquals("Connection refused to Valhalla", viewModel.routeGenerationError.value)
    }

    @Test
    fun test18_BleFailureDoesNotDiscardGeneratedRoute() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()
        val generatedBeforeTransfer = viewModel.generatedRoute.value
        org.junit.Assert.assertNotNull(generatedBeforeTransfer)

        // Simulate BLE error
        fakeBleRepo.simulateError()
        advanceUntilIdle()

        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        // Generated route must NOT be discarded
        assertEquals("Generated route must NOT be discarded on BLE failure", generatedBeforeTransfer, viewModel.generatedRoute.value)
    }

    @Test
    fun test19_AfterBleFailureRetryingSendDoesNotCallValhallaAgain() = runTest(testDispatcher) {
        val fakeResult = createFakeConversionResult()
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()
        assertEquals(1, fakeValhallaRepo.fetchRouteCallCount)

        // Attempt 1: BLE fails
        fakeBleRepo.simulateError()
        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        // Reconnect BLE
        fakeBleRepo.connectionState.value = ConnectionState.Connected
        fakeBleRepo.resetError()
        advanceUntilIdle()

        // Attempt 2: retry Send
        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()

        assertEquals("Valhalla must NOT be called again on retry after BLE failure", 1, fakeValhallaRepo.fetchRouteCallCount)
        assertEquals("BLE transfer must have been invoked", 1, fakeBleRepo.transferSerializedRouteCallCount)
    }

    @Test
    fun test20_SecondSendAttemptUsesExactSameBinaryAndCrc() = runTest(testDispatcher) {
        val expectedBinary = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val expectedCrc = 0x12345678L
        val fakeResult = createFakeConversionResult(binary = expectedBinary, crc32 = expectedCrc)
        fakeValhallaRepo.resultToReturn = Result.success(fakeResult)

        viewModel.generateRoute()
        advanceUntilIdle()

        // First send
        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()
        val firstBinary = fakeBleRepo.lastTransferredBinary
        val firstCrc = fakeBleRepo.lastTransferredCrc32

        // Second send
        viewModel.sendRouteToMotoNav()
        advanceUntilIdle()
        val secondBinary = fakeBleRepo.lastTransferredBinary
        val secondCrc = fakeBleRepo.lastTransferredCrc32

        assertArrayEquals("First send binary must match expected", expectedBinary, firstBinary)
        assertEquals("First send CRC must match expected", expectedCrc, firstCrc)
        assertArrayEquals("Second send binary must match first send binary", firstBinary, secondBinary)
        assertEquals("Second send CRC must match first send CRC", firstCrc, secondCrc)
    }

    @Test
    fun testSelectingRealSearchResultUpdatesStart() = runTest(testDispatcher) {
        val searchLoc = SearchLocation(
            id = "osm_101",
            name = "Hubballi Railway Station",
            address = "Station Road, Hubballi",
            latitude = 15.3486,
            longitude = 75.1481
        )

        val success = viewModel.selectStartLocation(searchLoc)
        assertTrue("Selecting valid search location as start should succeed", success)

        val start = viewModel.startLocation.value
        assertNotNull(start)
        assertEquals("Hubballi Railway Station", start!!.name)
        assertEquals(15.3486, start.latitude, 0.00001)
        assertEquals(75.1481, start.longitude, 0.00001)
    }

    @Test
    fun testSelectingRealSearchResultUpdatesDestination() = runTest(testDispatcher) {
        val searchLoc = SearchLocation(
            id = "osm_202",
            name = "Goa Airport",
            address = "Dabolim, Goa",
            latitude = 15.3800,
            longitude = 73.8314
        )

        val success = viewModel.selectDestination(searchLoc)
        assertTrue("Selecting valid search location as destination should succeed", success)

        val dest = viewModel.destination.value
        assertNotNull(dest)
        assertEquals("Goa Airport", dest!!.name)
        assertEquals(15.3800, dest.latitude, 0.00001)
        assertEquals(73.8314, dest.longitude, 0.00001)
    }

    @Test
    fun testDebouncingCancelsIntermediateRequests() = runTest(testDispatcher) {
        // Rapid keystrokes within debounce window (300ms)
        viewModel.searchLocations("D", debounceMs = 300L)
        advanceTimeBy(100)
        viewModel.searchLocations("Dh", debounceMs = 300L)
        advanceTimeBy(100)
        viewModel.searchLocations("Dharwad", debounceMs = 300L)
        advanceTimeBy(350)
        advanceUntilIdle()

        // Only the final query "Dharwad" should have triggered the repository
        assertEquals("Only one repository search should execute after debounce", 1, fakeLocationRepo.searchCallCount)
        assertEquals("Last query searched should be the debounced query", "Dharwad", fakeLocationRepo.lastQuery)
    }

    @Test
    fun testEmptySearchQueryRestoresPopularLocations() = runTest(testDispatcher) {
        // Perform search
        viewModel.searchLocations("KLE", debounceMs = 0L)
        advanceUntilIdle()

        // Clear query
        viewModel.searchLocations("", debounceMs = 0L)
        advanceUntilIdle()

        val results = viewModel.locationSearchResults.value
        assertEquals("Empty search query should restore popular locations", fakeLocationRepo.popularList.size, results.size)
        assertNull("Search error should be cleared on empty query", viewModel.locationSearchError.value)
    }

    @Test
    fun testNetworkFailureSetsLocationSearchError() = runTest(testDispatcher) {
        val fallbackList = fakeLocationRepo.popularList
        fakeLocationRepo.searchHandler = {
            LocationSearchResult.NetworkError("Unable to search locations. Check your internet connection.", fallbackList)
        }

        viewModel.searchLocations("Arbitrary Place", debounceMs = 0L)
        advanceUntilIdle()

        assertEquals("Unable to search locations. Check your internet connection.", viewModel.locationSearchError.value)
        assertEquals(fallbackList.size, viewModel.locationSearchResults.value.size)
    }

    @Test
    fun testEmptyResultSetsNoSearchError() = runTest(testDispatcher) {
        fakeLocationRepo.searchHandler = { q ->
            LocationSearchResult.Empty(q)
        }

        viewModel.searchLocations("nonexistent place", debounceMs = 0L)
        advanceUntilIdle()

        assertNull("Empty result should NOT set an error", viewModel.locationSearchError.value)
        assertTrue("Empty result should produce empty location results", viewModel.locationSearchResults.value.isEmpty())
    }

    @Test
    fun testSelectingMalformedLocationIsSafelyRejected() = runTest(testDispatcher) {
        val invalidLoc = SearchLocation(
            id = "bad_1",
            name = "Invalid Coord Place",
            address = "Unknown",
            latitude = 999.0, // Invalid latitude > 90
            longitude = 75.0
        )

        val startSuccess = viewModel.selectStartLocation(invalidLoc)
        assertFalse("Selecting invalid coordinates should return false", startSuccess)
        assertEquals("Start location coordinates are invalid", viewModel.locationValidationError.value)

        val destSuccess = viewModel.selectDestination(invalidLoc)
        assertFalse("Selecting invalid coordinates should return false", destSuccess)
        assertEquals("Destination coordinates are invalid", viewModel.locationValidationError.value)
    }
}
