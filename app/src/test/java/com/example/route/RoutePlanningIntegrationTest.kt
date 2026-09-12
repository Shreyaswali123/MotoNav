package com.example.route

import com.example.ble.BleRepository
import com.example.data.DefaultLocationSearchRepository
import com.example.data.SampleRouteRepository
import com.example.model.BleDiagnostics
import com.example.model.ConnectionState
import com.example.model.MotoNavDevice
import com.example.model.RoutePoint
import com.example.model.RouteTransferProgress
import com.example.network.ValhallaApi
import com.example.network.ValhallaRouteRepository
import com.example.settings.InMemorySettingsRepository
import com.example.ui.screens.home.HomeViewModel
import com.example.ui.screens.route.RouteGenerationState
import com.example.ui.screens.route.RouteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.CRC32

/**
 * End-to-end JVM integration test for the MotoNav Route Planning Pipeline (Issue #12A).
 *
 * Exercises the complete cross-component execution:
 * RouteViewModel
 *     ↓
 * real ValhallaRouteRepository (backed by intercepted OkHttp client returning ValhallaRouteFixture)
 *     ↓
 * real MotoNavRouteConverter (polyline decoding, Douglas-Peucker simplification, maneuver conversion)
 *     ↓
 * real MotoNav v1 serialization and CRC32 calculation
 *     ↓
 * RouteViewModel generated and active route state (observed by HomeViewModel)
 *     ↓
 * BLE transferSerializedRoute() trigger
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlanningIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    /**
     * Test-local fake BLE repository that records route transfer invocations
     * without requiring physical BLE hardware or GATT internals.
     */
    private class TestBleRepository : BleRepository {
        override val connectionState = MutableStateFlow(ConnectionState.Connected)
        override val connectedDevice = MutableStateFlow<MotoNavDevice?>(null)
        override val diagnostics = MutableStateFlow(BleDiagnostics())
        override val transferProgress = MutableStateFlow(RouteTransferProgress())
        override val lastError = MutableStateFlow<String?>(null)

        var transferSerializedRouteCallCount = 0
        var lastTransferredBinary: ByteArray? = null
        var lastTransferredCrc32: Long? = null

        override fun connect(deviceId: String) {}
        override fun disconnect() {}
        override fun readStatus() {}
        override fun sendTestRoute() {}

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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Creates an intercepted OkHttpClient returning a deterministic HTTP response
     * without performing any network I/O.
     */
    private fun createInterceptedClient(
        statusCode: Int = 200,
        responseJson: String = ValhallaRouteFixture.JSON_RESPONSE
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode == 200) "OK" else "Error")
                    .body(
                        responseJson.toResponseBody(
                            if (statusCode == 200) "application/json".toMediaType() else "text/plain".toMediaType()
                        )
                    )
                    .build()
            }
            .build()
    }

    /**
     * Creates an intercepted OkHttpClient that throws an IOException on network dispatch,
     * simulating a connection failure, DNS resolution error, or socket timeout.
     */
    private fun createThrowingClient(exception: IOException): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor {
                throw exception
            }
            .build()
    }

    /**
     * Advances the test coroutine scheduler and polls until RouteViewModel finishes route generation.
     * Thread sleep allows Dispatchers.IO background thread pool to execute the intercepted HTTP
     * call and route conversion computations.
     */
    private fun awaitGenerationToComplete(
        viewModel: RouteViewModel,
        timeoutMs: Long = 5000L
    ) {
        val startTime = System.currentTimeMillis()
        while (viewModel.isGeneratingRoute.value) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw TimeoutException("Route generation timed out after $timeoutMs ms")
            }
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }
        testDispatcher.scheduler.advanceUntilIdle()
    }

    /**
     * Independently inspects the captured byte array according to the MotoNav v1 format specification
     * without calling MotoNavV1Serializer, ensuring the binary structure is validated from first principles.
     */
    private fun independentlyValidateCapturedBinary(
        binary: ByteArray,
        expectedCrc32: Long,
        expectedOrigin: RoutePoint,
        expectedDestination: RoutePoint
    ) {
        // 1. Minimum 9-byte header verification
        assertTrue(
            "Captured binary must be at least 9 bytes for the header, was ${binary.size}",
            binary.size >= 9
        )

        val buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)

        // 2. Header parsing
        val version = buffer.get()
        assertEquals("Protocol version must be 1 (uint8)", 1.toByte(), version)

        val routeId = buffer.getInt().toLong() and 0xFFFFFFFFL
        assertTrue("Route ID must be a non-negative 32-bit integer", routeId >= 0L)

        val pointCount = buffer.getShort().toInt() and 0xFFFF
        assertTrue("Point count must be > 0, was $pointCount", pointCount > 0)

        val maneuverCount = buffer.getShort().toInt() and 0xFFFF
        assertTrue("Maneuver count must be > 0, was $maneuverCount", maneuverCount > 0)

        // 3. Exact binary size formula check: 9 + pointCount * 8 + maneuverCount * 7
        val expectedLength = 9 + (pointCount * 8) + (maneuverCount * 7)
        assertEquals(
            "Total binary length must exactly match formula 9 + (points * 8) + (maneuvers * 7)",
            expectedLength,
            binary.size
        )

        // 4. Inspect point records (8 bytes each: int32 lat*1e7, int32 lon*1e7)
        val decodedPoints = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until pointCount) {
            val latScaled = buffer.getInt()
            val lonScaled = buffer.getInt()
            val lat = latScaled / 1e7
            val lon = lonScaled / 1e7

            assertTrue("Point $i latitude $lat must be in [-90, 90]", lat in -90.0..90.0)
            assertTrue("Point $i longitude $lon must be in [-180, 180]", lon in -180.0..180.0)
            decodedPoints.add(Pair(lat, lon))
        }

        // Validate origin coordinate correspondence
        val firstPoint = decodedPoints.first()
        assertEquals(
            "First point latitude must correspond to origin",
            expectedOrigin.latitude,
            firstPoint.first,
            0.02
        )
        assertEquals(
            "First point longitude must correspond to origin",
            expectedOrigin.longitude,
            firstPoint.second,
            0.02
        )

        // Validate destination coordinate correspondence
        val lastPoint = decodedPoints.last()
        assertEquals(
            "Last point latitude must correspond to destination",
            expectedDestination.latitude,
            lastPoint.first,
            0.02
        )
        assertEquals(
            "Last point longitude must correspond to destination",
            expectedDestination.longitude,
            lastPoint.second,
            0.02
        )

        // 5. Inspect maneuver records (7 bytes each: uint16 pt_idx, uint8 type, uint32 distance)
        val validManeuverTypes = setOf<Byte>(1, 2, 5) // 1=LEFT, 2=RIGHT, 5=ARRIVE
        var previousPointIndex = -1
        for (i in 0 until maneuverCount) {
            val ptIdx = buffer.getShort().toInt() and 0xFFFF
            val maneuverType = buffer.get()
            val distanceMeters = buffer.getInt().toLong() and 0xFFFFFFFFL

            assertTrue("Maneuver $i point index ($ptIdx) must be within points [0, $pointCount)", ptIdx in 0 until pointCount)
            assertTrue(
                "Maneuver point indices must be non-decreasing (previous: $previousPointIndex, current: $ptIdx)",
                ptIdx >= previousPointIndex
            )
            previousPointIndex = ptIdx

            assertTrue(
                "Maneuver $i type ($maneuverType) must be one of $validManeuverTypes",
                maneuverType in validManeuverTypes
            )
            assertTrue("Maneuver $i distance ($distanceMeters) must be non-negative", distanceMeters >= 0L)
        }

        // All bytes consumed
        assertEquals("Buffer position must equal total binary length", binary.size, buffer.position())

        // 6. Independent CRC32 verification using standard java.util.zip.CRC32
        val crcCalculator = CRC32()
        crcCalculator.update(binary)
        val independentlyCalculatedCrc = crcCalculator.value and 0xFFFFFFFFL

        assertEquals(
            "Independently calculated CRC32 must match the transfer CRC32 exactly",
            expectedCrc32,
            independentlyCalculatedCrc
        )
    }

    @Test
    fun testFullRoutePlanningPipeline_HappyPath_GeneratesValidBinaryAndDispatchesToBle() = runTest(testDispatcher) {
        // 1. Build real components with intercepted OkHttp transport and test BLE repository
        val interceptedClient = createInterceptedClient(
            statusCode = 200,
            responseJson = ValhallaRouteFixture.JSON_RESPONSE
        )
        val valhallaApi = ValhallaApi.create(okHttpClient = interceptedClient)
        val realValhallaRepo = ValhallaRouteRepository(api = valhallaApi)
        val testBleRepo = TestBleRepository()
        val sampleRouteRepo = SampleRouteRepository()
        val settingsRepo = InMemorySettingsRepository()

        val routeViewModel = RouteViewModel(
            routeRepository = sampleRouteRepo,
            bleRepository = testBleRepo,
            settingsRepository = settingsRepo,
            valhallaRouteRepository = realValhallaRepo,
            locationSearchRepository = DefaultLocationSearchRepository.instance
        )

        // Wire HomeViewModel to observe the routeViewModel.selectedRoute StateFlow (production pattern)
        val homeViewModel = HomeViewModel(
            bleRepository = testBleRepo,
            activeRoute = routeViewModel.selectedRoute,
            settingsRepository = settingsRepo
        )

        // Verify initial state before generation
        assertNull("Initially, generatedRoute must be null", routeViewModel.generatedRoute.value)
        assertFalse("Send route must be disabled initially", routeViewModel.isSendRouteEnabled.value)

        // 2. Supply valid origin and destination coordinates matching the Valhalla fixture (Hubballi -> Dharwad)
        val origin = RoutePoint(latitude = 15.35, longitude = 75.1491, name = "Hubballi Junction")
        val destination = RoutePoint(latitude = 15.44039, longitude = 75.00455, name = "Dharwad Center")

        assertTrue("Origin selection must succeed", routeViewModel.selectStartLocation(origin))
        assertTrue("Destination selection must succeed", routeViewModel.selectDestination(destination))

        // 3. Invoke real route generation operation
        routeViewModel.generateRoute()

        // 4. Advance and await completion across Dispatchers.IO and test dispatcher
        awaitGenerationToComplete(routeViewModel)

        // 5. Verify route generation succeeded and populated ViewModel state
        val genState = routeViewModel.routeGenerationState.value
        assertTrue("Generation state must be Success, but was $genState", genState is RouteGenerationState.Success)
        assertFalse("isGeneratingRoute must be false after completion", routeViewModel.isGeneratingRoute.value)

        val generatedState = routeViewModel.generatedRoute.value
        assertNotNull("GeneratedRouteState must be non-null after successful generation", generatedState)
        requireNotNull(generatedState)

        val selectedRoute = routeViewModel.selectedRoute.value
        assertEquals("Origin must match in selectedRoute", origin, selectedRoute.startLocation)
        assertEquals("Destination must match in selectedRoute", destination, selectedRoute.destination)

        // Geometry contains meaningful simplified points (203 points for the known fixture)
        assertEquals("Simplified geometry must have 203 points", 203, selectedRoute.waypoints.size)

        // Maneuvers are present (20 supported maneuvers converted from Valhalla)
        assertEquals("Converted maneuvers must contain 20 maneuvers", 20, selectedRoute.maneuvers.size)

        // Metrics are positive
        assertEquals("Total distance must match fixture summary (26547 meters)", 26547, selectedRoute.totalDistanceMeters)
        assertEquals("Estimated duration must match fixture summary (3849 seconds)", 3849, selectedRoute.estimatedDurationSeconds)

        // Verify HomeViewModel observes the exact same generated active route
        val homeActiveRoute = homeViewModel.activeRoute.value
        assertEquals("Home active route must reflect the generated route", selectedRoute, homeActiveRoute)
        assertEquals(203, homeActiveRoute.waypoints.size)
        assertEquals(20, homeActiveRoute.maneuvers.size)
        assertEquals(26547, homeActiveRoute.totalDistanceMeters)
        assertEquals(3849, homeActiveRoute.estimatedDurationSeconds)

        // Send to MotoNav button must now be enabled
        assertTrue("Send route to MotoNav must be enabled after generation", routeViewModel.isSendRouteEnabled.value)

        // 6. Invoke real RouteViewModel BLE-send operation
        routeViewModel.sendRouteToMotoNav()

        // 7. Verify fake BLE repository received exactly one serialized route
        assertEquals(
            "transferSerializedRoute must be called exactly once",
            1,
            testBleRepo.transferSerializedRouteCallCount
        )

        val capturedBinary = testBleRepo.lastTransferredBinary
        assertNotNull("Captured binary must not be null", capturedBinary)
        requireNotNull(capturedBinary)

        val capturedCrc32 = testBleRepo.lastTransferredCrc32
        assertNotNull("Captured CRC32 must not be null", capturedCrc32)
        requireNotNull(capturedCrc32)

        assertEquals("Binary passed to BLE must match stored binary in GeneratedRouteState", generatedState.serializedBinary, capturedBinary)
        assertEquals("CRC passed to BLE must match stored CRC in GeneratedRouteState", generatedState.crc32, capturedCrc32)

        // 8. Independently validate the captured binary structure and CRC
        independentlyValidateCapturedBinary(
            binary = capturedBinary,
            expectedCrc32 = capturedCrc32,
            expectedOrigin = origin,
            expectedDestination = destination
        )
    }

    @Test
    fun testRoutePlanningPipeline_ValhallaHttpError_PreventsBleTransfer() = runTest(testDispatcher) {
        // 1. Interceptor returns HTTP 500
        val errorClient = createInterceptedClient(
            statusCode = 500,
            responseJson = "Internal Server Error"
        )
        val valhallaApi = ValhallaApi.create(okHttpClient = errorClient)
        val realValhallaRepo = ValhallaRouteRepository(api = valhallaApi)
        val testBleRepo = TestBleRepository()
        val sampleRouteRepo = SampleRouteRepository()
        val settingsRepo = InMemorySettingsRepository()

        val routeViewModel = RouteViewModel(
            routeRepository = sampleRouteRepo,
            bleRepository = testBleRepo,
            settingsRepository = settingsRepo,
            valhallaRouteRepository = realValhallaRepo,
            locationSearchRepository = DefaultLocationSearchRepository.instance
        )

        val origin = RoutePoint(latitude = 15.35, longitude = 75.1491, name = "Hubballi Junction")
        val destination = RoutePoint(latitude = 15.44039, longitude = 75.00455, name = "Dharwad Center")

        routeViewModel.selectStartLocation(origin)
        routeViewModel.selectDestination(destination)

        // 2. Invoke real route generation
        routeViewModel.generateRoute()
        awaitGenerationToComplete(routeViewModel)

        // 3. Verify failure in RouteViewModel state
        val genState = routeViewModel.routeGenerationState.value
        assertTrue(
            "State should be RouteGenerationError on HTTP failure, was $genState",
            genState is RouteGenerationState.RouteGenerationError
        )
        assertNull("Generated route state must remain null on failure", routeViewModel.generatedRoute.value)
        assertFalse("Send route must be disabled on failure", routeViewModel.isSendRouteEnabled.value)

        // 4. Attempting to send should not trigger BLE transfer
        routeViewModel.sendRouteToMotoNav()
        assertEquals(
            "BLE transfer must NOT be triggered after route generation failure",
            0,
            testBleRepo.transferSerializedRouteCallCount
        )
        assertNull("No binary should be passed to BLE", testBleRepo.lastTransferredBinary)
    }

    /**
     * TEST 1 (Issue #12C): Network IOException (Connection failure / timeout).
     *
     * Proves:
     * OkHttp IOException -> Retrofit suspend call -> ValhallaRouteRepository catch -> Result.failure -> RouteViewModel error state.
     * Verifies that network-level IOExceptions are properly contained, enter RouteGenerationError
     * containing the exception message, leave generatedRoute null, disable sending, and prevent BLE dispatch.
     */
    @Test
    fun testRoutePlanningPipeline_NetworkIOException_EntersErrorStateAndPreventsBle() = runTest(testDispatcher) {
        val networkException = IOException("Connection refused to Valhalla host")
        val throwingClient = createThrowingClient(networkException)
        val valhallaApi = ValhallaApi.create(okHttpClient = throwingClient)
        val realValhallaRepo = ValhallaRouteRepository(api = valhallaApi)
        val testBleRepo = TestBleRepository()
        val sampleRouteRepo = SampleRouteRepository()
        val settingsRepo = InMemorySettingsRepository()

        val routeViewModel = RouteViewModel(
            routeRepository = sampleRouteRepo,
            bleRepository = testBleRepo,
            settingsRepository = settingsRepo,
            valhallaRouteRepository = realValhallaRepo,
            locationSearchRepository = DefaultLocationSearchRepository.instance
        )

        val origin = RoutePoint(latitude = 15.35, longitude = 75.1491, name = "Hubballi Junction")
        val destination = RoutePoint(latitude = 15.44039, longitude = 75.00455, name = "Dharwad Center")

        routeViewModel.selectStartLocation(origin)
        routeViewModel.selectDestination(destination)

        routeViewModel.generateRoute()
        awaitGenerationToComplete(routeViewModel)

        val genState = routeViewModel.routeGenerationState.value
        assertTrue(
            "State should be RouteGenerationError on network IOException, was $genState",
            genState is RouteGenerationState.RouteGenerationError
        )
        val errorState = genState as RouteGenerationState.RouteGenerationError
        assertTrue(
            "Error state message must contain 'Connection refused to Valhalla host', was: ${errorState.message}",
            errorState.message.contains("Connection refused to Valhalla host")
        )
        assertEquals(
            "routeGenerationError StateFlow must contain 'Connection refused to Valhalla host'",
            errorState.message,
            routeViewModel.routeGenerationError.value
        )
        assertNull("Generated route state must remain null on network failure", routeViewModel.generatedRoute.value)
        assertFalse("Send route must be disabled on network failure", routeViewModel.isSendRouteEnabled.value)

        // Attempting to send should not trigger BLE transfer
        routeViewModel.sendRouteToMotoNav()
        assertEquals(
            "BLE transfer must NOT be triggered after network failure",
            0,
            testBleRepo.transferSerializedRouteCallCount
        )
        assertNull("No binary should be passed to BLE", testBleRepo.lastTransferredBinary)
    }

    /**
     * TEST 2 (Issue #12C): Malformed / Truncated JSON response.
     *
     * Proves:
     * Intercepted HTTP 200 with invalid JSON -> Moshi deserialization failure -> ValhallaRouteRepository catch -> Result.failure -> RouteViewModel error state.
     * Verifies that parser exceptions are safely contained by the repository boundary, enter RouteGenerationError,
     * leave generatedRoute null, disable sending, and prevent BLE dispatch.
     */
    @Test
    fun testRoutePlanningPipeline_MalformedJson_EntersErrorStateAndPreventsBle() = runTest(testDispatcher) {
        val malformedJson = """{"trip": { "legs": [ { "shape": """
        val malformedClient = createInterceptedClient(
            statusCode = 200,
            responseJson = malformedJson
        )
        val valhallaApi = ValhallaApi.create(okHttpClient = malformedClient)
        val realValhallaRepo = ValhallaRouteRepository(api = valhallaApi)
        val testBleRepo = TestBleRepository()
        val sampleRouteRepo = SampleRouteRepository()
        val settingsRepo = InMemorySettingsRepository()

        val routeViewModel = RouteViewModel(
            routeRepository = sampleRouteRepo,
            bleRepository = testBleRepo,
            settingsRepository = settingsRepo,
            valhallaRouteRepository = realValhallaRepo,
            locationSearchRepository = DefaultLocationSearchRepository.instance
        )

        val origin = RoutePoint(latitude = 15.35, longitude = 75.1491, name = "Hubballi Junction")
        val destination = RoutePoint(latitude = 15.44039, longitude = 75.00455, name = "Dharwad Center")

        routeViewModel.selectStartLocation(origin)
        routeViewModel.selectDestination(destination)

        routeViewModel.generateRoute()
        awaitGenerationToComplete(routeViewModel)

        val genState = routeViewModel.routeGenerationState.value
        assertTrue(
            "State should be RouteGenerationError on malformed JSON, was $genState",
            genState is RouteGenerationState.RouteGenerationError
        )
        val errorMsg = (genState as RouteGenerationState.RouteGenerationError).message
        assertNotNull("Error message must not be null", errorMsg)
        assertTrue("Error message must not be empty", errorMsg.isNotBlank())
        assertNull("Generated route state must remain null on parser failure", routeViewModel.generatedRoute.value)
        assertFalse("Send route must be disabled on parser failure", routeViewModel.isSendRouteEnabled.value)

        // Attempting to send should not trigger BLE transfer
        routeViewModel.sendRouteToMotoNav()
        assertEquals(
            "BLE transfer must NOT be triggered after malformed JSON failure",
            0,
            testBleRepo.transferSerializedRouteCallCount
        )
        assertNull("No binary should be passed to BLE", testBleRepo.lastTransferredBinary)
    }

    /**
     * TEST 3 (Issue #12C): Valhalla business error (HTTP 200 with trip == null).
     *
     * Proves:
     * Moshi deserialization of Valhalla error response -> response.trip == null branch in ValhallaRouteRepository ->
     * Result.failure(IllegalStateException(statusMessage)) -> RouteViewModel error state preserving "No suitable edges near location".
     * Verifies that business-level routing errors surface the upstream error message cleanly,
     * leave generatedRoute null, disable sending, and prevent BLE dispatch.
     */
    @Test
    fun testRoutePlanningPipeline_ValhallaTripNullResponse_PreservesErrorMessageAndPreventsBle() = runTest(testDispatcher) {
        val tripNullJson = """
            {
              "status": 171,
              "status_message": "No suitable edges near location",
              "trip": null
            }
        """.trimIndent()
        val errorClient = createInterceptedClient(
            statusCode = 200,
            responseJson = tripNullJson
        )
        val valhallaApi = ValhallaApi.create(okHttpClient = errorClient)
        val realValhallaRepo = ValhallaRouteRepository(api = valhallaApi)
        val testBleRepo = TestBleRepository()
        val sampleRouteRepo = SampleRouteRepository()
        val settingsRepo = InMemorySettingsRepository()

        val routeViewModel = RouteViewModel(
            routeRepository = sampleRouteRepo,
            bleRepository = testBleRepo,
            settingsRepository = settingsRepo,
            valhallaRouteRepository = realValhallaRepo,
            locationSearchRepository = DefaultLocationSearchRepository.instance
        )

        val origin = RoutePoint(latitude = 15.35, longitude = 75.1491, name = "Hubballi Junction")
        val destination = RoutePoint(latitude = 15.44039, longitude = 75.00455, name = "Dharwad Center")

        routeViewModel.selectStartLocation(origin)
        routeViewModel.selectDestination(destination)

        routeViewModel.generateRoute()
        awaitGenerationToComplete(routeViewModel)

        val genState = routeViewModel.routeGenerationState.value
        assertTrue(
            "State should be RouteGenerationError on trip == null response, was $genState",
            genState is RouteGenerationState.RouteGenerationError
        )
        val errorState = genState as RouteGenerationState.RouteGenerationError
        assertTrue(
            "Error state message must contain 'No suitable edges near location', was: ${errorState.message}",
            errorState.message.contains("No suitable edges near location")
        )
        assertEquals(
            "routeGenerationError StateFlow must contain 'No suitable edges near location'",
            errorState.message,
            routeViewModel.routeGenerationError.value
        )
        assertNull("Generated route state must remain null on trip == null response", routeViewModel.generatedRoute.value)
        assertFalse("Send route must be disabled on trip == null response", routeViewModel.isSendRouteEnabled.value)

        // Attempting to send should not trigger BLE transfer
        routeViewModel.sendRouteToMotoNav()
        assertEquals(
            "BLE transfer must NOT be triggered after trip == null failure",
            0,
            testBleRepo.transferSerializedRouteCallCount
        )
        assertNull("No binary should be passed to BLE", testBleRepo.lastTransferredBinary)
    }
}
