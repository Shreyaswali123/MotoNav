package com.example.data

import com.example.ble.BleRepository
import com.example.model.BleDiagnostics
import com.example.model.ConnectionState
import com.example.model.MotoNavDevice
import com.example.model.Route
import com.example.model.RoutePoint
import com.example.model.RouteTransferProgress
import com.example.network.ValhallaRouteRepository
import com.example.route.RouteConversionResult
import com.example.settings.InMemorySettingsRepository
import com.example.ui.screens.route.RouteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeographicSearchBiasRegressionTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeLocationSearchRepository : LocationSearchRepository {
        var lastQuery: String? = null
        var lastLat: Double? = null
        var lastLon: Double? = null
        var searchCallCount = 0

        var searchResultToReturn: LocationSearchResult = LocationSearchResult.Success(emptyList())

        override suspend fun search(query: String): LocationSearchResult {
            return search(query, null, null)
        }

        override suspend fun search(
            query: String,
            userLatitude: Double?,
            userLongitude: Double?
        ): LocationSearchResult {
            searchCallCount++
            lastQuery = query
            lastLat = userLatitude
            lastLon = userLongitude
            return searchResultToReturn
        }

        override suspend fun searchLocations(query: String): List<SearchLocation> = emptyList()
        override fun getPopularLocations(): List<SearchLocation> = emptyList()
        override suspend fun reverseGeocode(latitude: Double, longitude: Double): String? = null
    }

    private class FakeCurrentLocationProvider : CurrentLocationProvider {
        var resultToReturn: LocationResult = LocationResult.Unavailable()
        var callCount = 0

        override suspend fun getCurrentLocation(): LocationResult {
            callCount++
            return resultToReturn
        }
    }

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
        var fetchRouteCallCount = 0

        override suspend fun fetchRoute(
            origin: RoutePoint,
            destination: RoutePoint,
            routeId: Long
        ): Result<RouteConversionResult> {
            fetchRouteCallCount++
            return Result.failure(IllegalStateException("No fake result set in test"))
        }
    }

    private lateinit var fakeLocationRepo: FakeLocationSearchRepository
    private lateinit var fakeLocationProvider: FakeCurrentLocationProvider
    private lateinit var sampleRouteRepo: SampleRouteRepository
    private lateinit var settingsRepo: InMemorySettingsRepository
    private lateinit var viewModel: RouteViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeLocationRepo = FakeLocationSearchRepository()
        fakeLocationProvider = FakeCurrentLocationProvider()
        sampleRouteRepo = SampleRouteRepository()
        settingsRepo = InMemorySettingsRepository()

        viewModel = RouteViewModel(
            routeRepository = sampleRouteRepo,
            bleRepository = FakeBleRepository(),
            settingsRepository = settingsRepo,
            valhallaRouteRepository = FakeValhallaRouteRepository(),
            locationSearchRepository = fakeLocationRepo,
            currentLocationProvider = fakeLocationProvider
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // SECTION 10: SAMPLE ROUTE DEFENSE INVARIANT
    // =========================================================================

    @Test
    fun testSampleRouteDefense_SampleCoordinatesNeverUsedForSearchBias() {
        // San Francisco sample route coordinates
        val sampleLat = 37.7749
        val sampleLon = -122.4194

        assertTrue(
            "SearchBiasCoordinatesPolicy must flag sample route coordinates as invalid",
            SearchBiasCoordinatesPolicy.isSampleOrInvalid(sampleLat, sampleLon)
        )
        assertTrue(
            "SearchBiasCoordinatesPolicy must reject null latitude",
            SearchBiasCoordinatesPolicy.isSampleOrInvalid(null, sampleLon)
        )
        assertTrue(
            "SearchBiasCoordinatesPolicy must reject non-finite latitude",
            SearchBiasCoordinatesPolicy.isSampleOrInvalid(Double.NaN, sampleLon)
        )
        assertFalse(
            "Hubballi coordinates must be valid",
            SearchBiasCoordinatesPolicy.isSampleOrInvalid(15.3647, 75.1240)
        )
    }

    // =========================================================================
    // SECTION 11: LOCATION SOURCE TESTS (1 to 7)
    // =========================================================================

    @Test
    fun testLocationSource_1_SampleRouteStartPresent_PhoneLocationAvailable_UsesPhoneLocation() = runTest(testDispatcher) {
        // Sample Route.startLocation is San Francisco (37.7749, -122.4194)
        assertNotNull(viewModel.startLocation.value)
        assertEquals(37.7749, viewModel.startLocation.value!!.latitude, 0.001)

        // Phone current location is Hubballi
        val hubballiPoint = RoutePoint(latitude = 15.3647, longitude = 75.1240, name = "Hubballi, India")
        viewModel.setAcquiredPhoneLocation(hubballiPoint)

        viewModel.searchLocations("gokul road", debounceMs = 0L)
        advanceUntilIdle()

        assertEquals("gokul road", fakeLocationRepo.lastQuery)
        assertEquals("Search bias must use physical phone latitude", 15.3647, fakeLocationRepo.lastLat!!, 0.0001)
        assertEquals("Search bias must use physical phone longitude", 75.1240, fakeLocationRepo.lastLon!!, 0.0001)
        assertNotEquals(
            "Search bias must NEVER use sample route start coordinates",
            37.7749,
            fakeLocationRepo.lastLat!!,
            0.001
        )
    }

    @Test
    fun testLocationSource_2_SampleRouteStartPresent_PhoneLocationUnavailable_DoesNotUseSampleCoordinates() = runTest(testDispatcher) {
        // Sample Route.startLocation is present
        assertNotNull(viewModel.startLocation.value)

        // Phone location is NOT available
        viewModel.setAcquiredPhoneLocation(null)

        viewModel.searchLocations("gokul road", debounceMs = 0L)
        advanceUntilIdle()

        assertNull("Search latitude must be null when phone location is unavailable", fakeLocationRepo.lastLat)
        assertNull("Search longitude must be null when phone location is unavailable", fakeLocationRepo.lastLon)
    }

    @Test
    fun testLocationSource_3_ManuallySelectedStartPresent_PhoneLocationAvailable_UsesPhoneLocation() = runTest(testDispatcher) {
        // User manually selected Goa as route origin
        val goaStart = SearchLocation(
            id = "loc_goa",
            name = "Panaji Bus Stand",
            address = "Panaji, Goa, India",
            latitude = 15.4909,
            longitude = 73.8278
        )
        viewModel.selectStartLocation(goaStart)
        assertEquals(15.4909, viewModel.startLocation.value!!.latitude, 0.001)

        // Rider is physically in Hubballi
        viewModel.setAcquiredPhoneLocation(RoutePoint(latitude = 15.3647, longitude = 75.1240, name = "Hubballi Current Location"))

        // Rider searches for an endpoint
        viewModel.searchLocations("vidyanagar", debounceMs = 0L)
        advanceUntilIdle()

        assertEquals("Search latitude must remain phone location", 15.3647, fakeLocationRepo.lastLat!!, 0.0001)
        assertEquals("Search longitude must remain phone location", 75.1240, fakeLocationRepo.lastLon!!, 0.0001)
        assertNotEquals(
            "Search bias must not use manual route origin",
            15.4909,
            fakeLocationRepo.lastLat!!,
            0.001
        )
    }

    @Test
    fun testLocationSource_4_DroppedDestinationPinPresent_PhoneLocationAvailable_UsesPhoneLocation() = runTest(testDispatcher) {
        // Rider drops destination pin in Belagavi
        viewModel.setPinnedLocation(
            isStart = false,
            latitude = 15.8497,
            longitude = 74.4977,
            resolvedName = "Belagavi Fort"
        )
        assertEquals(15.8497, viewModel.destination.value!!.latitude, 0.001)

        // Phone location is Hubballi
        viewModel.setAcquiredPhoneLocation(RoutePoint(latitude = 15.3647, longitude = 75.1240, name = "Hubballi"))

        viewModel.searchLocations("kims hospital", debounceMs = 0L)
        advanceUntilIdle()

        assertEquals("Search bias must use phone location", 15.3647, fakeLocationRepo.lastLat!!, 0.0001)
        assertEquals("Search bias must use phone location", 75.1240, fakeLocationRepo.lastLon!!, 0.0001)
        assertNotEquals(
            "Search bias must not use dropped destination pin",
            15.8497,
            fakeLocationRepo.lastLat!!,
            0.001
        )
    }

    @Test
    fun testLocationSource_5_CurrentLocationRouteOriginSelected_SeparateStateConcepts() = runTest(testDispatcher) {
        fakeLocationProvider.resultToReturn = LocationResult.Success(15.3647, 75.1240)
        viewModel.useCurrentLocation(hasPermission = true)
        advanceUntilIdle()

        // Route origin and search cache both have phone coordinates
        assertEquals(15.3647, viewModel.startLocation.value!!.latitude, 0.0001)
        assertEquals(15.3647, viewModel.getAcquiredPhoneLocation()!!.latitude, 0.0001)

        // Clear route start location
        viewModel.clearStartLocation()
        advanceUntilIdle()

        assertNull("Route start location must be cleared", viewModel.startLocation.value)
        assertNotNull("Phone physical location cache must remain intact", viewModel.getAcquiredPhoneLocation())
        assertEquals(15.3647, viewModel.getAcquiredPhoneLocation()!!.latitude, 0.0001)

        // Subsequent search still uses phone location
        viewModel.searchLocations("clock tower", debounceMs = 0L)
        advanceUntilIdle()

        assertEquals(15.3647, fakeLocationRepo.lastLat!!, 0.0001)
        assertEquals(75.1240, fakeLocationRepo.lastLon!!, 0.0001)
    }

    @Test
    fun testLocationSource_6_CurrentPhoneLocationUnavailable_SendsNullRatherThanSampleCoordinates() = runTest(testDispatcher) {
        viewModel.setAcquiredPhoneLocation(null)

        viewModel.searchLocations("budanagudda", debounceMs = 0L)
        advanceUntilIdle()

        assertNull("Search latitude must be null", fakeLocationRepo.lastLat)
        assertNull("Search longitude must be null", fakeLocationRepo.lastLon)
        assertNotEquals("Must NEVER send sample coordinates", 37.7749, fakeLocationRepo.lastLat ?: 0.0, 0.001)
    }

    @Test
    fun testLocationSource_7_CurrentPhoneLocationRefresh_SubsequentSearchUsesRefreshedCoordinates() = runTest(testDispatcher) {
        // Initial location: Hubballi
        fakeLocationProvider.resultToReturn = LocationResult.Success(15.3647, 75.1240)
        viewModel.refreshPhoneLocationForSearch(hasPermission = true)
        advanceUntilIdle()

        viewModel.searchLocations("market", debounceMs = 0L)
        advanceUntilIdle()
        assertEquals(15.3647, fakeLocationRepo.lastLat!!, 0.0001)

        // Rider travels to Dharwad
        fakeLocationProvider.resultToReturn = LocationResult.Success(15.4589, 75.0078)
        viewModel.refreshPhoneLocationForSearch(hasPermission = true)
        advanceUntilIdle()

        viewModel.searchLocations("market", debounceMs = 0L)
        advanceUntilIdle()
        assertEquals(15.4589, fakeLocationRepo.lastLat!!, 0.0001)
        assertEquals(75.0078, fakeLocationRepo.lastLon!!, 0.0001)
    }

    // =========================================================================
    // SECTION 12: PHOTON REQUEST CONSTRUCTION TESTS
    // =========================================================================

    @Test
    fun testPhotonRequestBuilder_HubballiCoordinatesPreservedAcrossAllQueries() = runTest {
        val interceptedUrls = CopyOnWriteArrayList<String>()
        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                interceptedUrls.add(chain.request().url.toString())
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"features":[]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val photonProvider = PhotonSearchProvider(httpClient = mockHttpClient)

        val testQueries = listOf("gokul road", "kims hospital", "vidyanagar", "budanagudda")
        val hubLat = 15.3647
        val hubLon = 75.1240

        for (query in testQueries) {
            photonProvider.search(query, lat = hubLat, lon = hubLon)
        }

        assertEquals("Should have made 4 requests", 4, interceptedUrls.size)
        for (url in interceptedUrls) {
            assertTrue("URL must contain lat=15.364700: $url", url.contains("lat=15.364700"))
            assertTrue("URL must contain lon=75.124000: $url", url.contains("lon=75.124000"))
        }
    }

    @Test
    fun testLocationSearchRepository_QueryVariantsPreserveSameCoordinates() = runTest {
        val requestedUrls = CopyOnWriteArrayList<String>()
        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestedUrls.add(chain.request().url.toString())
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"features":[]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val photonProvider = PhotonSearchProvider(httpClient = mockHttpClient)
        val repo = DefaultLocationSearchRepository(
            httpClient = mockHttpClient,
            photonProvider = photonProvider,
            delayer = {}
        )

        val hubLat = 15.3647
        val hubLon = 75.1240

        // "budanagudda" generates compound split variant "budan gudda"
        repo.search("budanagudda", hubLat, hubLon)

        assertTrue("Should have queried primary and variant", requestedUrls.size >= 2)
        for (url in requestedUrls) {
            if (url.contains("photon")) {
                assertTrue("Photon request must preserve lat=15.364700: $url", url.contains("lat=15.364700"))
                assertTrue("Photon request must preserve lon=75.124000: $url", url.contains("lon=75.124000"))
            }
        }
    }

    // =========================================================================
    // SECTION 13: STRONG RESULT TESTS (A to G)
    // =========================================================================

    @Test
    fun testStrongResult_A_NearExactMatch_IsStrong() {
        val hubballiUserLat = 15.3647
        val hubballiUserLon = 75.1240

        val nearExactCandidate = SearchLocation(
            id = "near_1",
            name = "KIMS Hospital",
            address = "Vidyanagar, Hubballi, Dharwad, Karnataka, India",
            latitude = 15.3582,
            longitude = 75.1293,
            placeType = "hospital",
            importance = 0.5
        )

        assertTrue(
            "Nearby exact match (< 2 km) must be classified as STRONG",
            PlaceResultRanker.isStrongResult(
                nearExactCandidate,
                "kims hospital",
                hubballiUserLat,
                hubballiUserLon
            )
        )
    }

    @Test
    fun testStrongResult_B_DistantExactMatchWithNoNearbyCandidate_RemainsDiscoverable() {
        val hubballiUserLat = 15.3647
        val hubballiUserLon = 75.1240

        val distantCandidate = SearchLocation(
            id = "dist_1",
            name = "Eiffel Tower",
            address = "Champ de Mars, 5 Avenue Anatole France, 75007 Paris, France",
            latitude = 48.8584,
            longitude = 2.2945,
            placeType = "attraction",
            importance = 0.95
        )

        val ranked = PlaceResultRanker.rankResults(
            listOf(distantCandidate),
            "eiffel tower",
            hubballiUserLat,
            hubballiUserLon
        )

        assertEquals("Distant unique destination must remain discoverable", 1, ranked.size)
        assertEquals("Eiffel Tower", ranked.first().name)
    }

    @Test
    fun testStrongResult_C_DistantExactMatchForAmbiguousQuery_DoesNotSuppressFallback() {
        val hubballiUserLat = 15.3647
        val hubballiUserLon = 75.1240

        // Gokul Road in Umlazi, South Africa (6,915 km away)
        val distantGokulRoad = SearchLocation(
            id = "sa_1",
            name = "Gokul Road",
            address = "Umlazi, eThekwini Metropolitan Municipality, KwaZulu-Natal, South Africa",
            latitude = -29.9702,
            longitude = 30.8845,
            placeType = "highway",
            importance = 0.3
        )

        val isStrong = PlaceResultRanker.isStrongResult(
            distantGokulRoad,
            "gokul road",
            hubballiUserLat,
            hubballiUserLon
        )

        assertFalse(
            "Distant exact match for generic/ambiguous query must NOT be classified as STRONG, allowing fallback",
            isStrong
        )
    }

    @Test
    fun testStrongResult_D_NearbyCandidateWithModerateTextMatch_BeatsDistantExactText() {
        val hubballiUserLat = 15.3647
        val hubballiUserLon = 75.1240

        // Distant exact match in South Africa
        val distantCandidate = SearchLocation(
            id = "distant_exact",
            name = "Gokul Road",
            address = "Umlazi, KwaZulu-Natal, South Africa",
            latitude = -29.9702,
            longitude = 30.8845
        )

        // Nearby moderate match (composite name in Hubballi)
        val nearbyModerate = SearchLocation(
            id = "nearby_moderate",
            name = "Gokul Road / Airport Road",
            address = "Gokul, Hubballi, Dharwad, Karnataka, India",
            latitude = 15.3620,
            longitude = 75.1180
        )

        val ranked = PlaceResultRanker.rankResults(
            listOf(distantCandidate, nearbyModerate),
            "gokul road",
            hubballiUserLat,
            hubballiUserLon
        )

        assertEquals(
            "Nearby moderate match must beat distant exact match for ambiguous query",
            "nearby_moderate",
            ranked.first().id
        )
    }

    @Test
    fun testStrongResult_E_ExplicitCityQualifiedQuery_DelhiAirport_RemainsDiscoverableAndStrong() {
        val hubballiUserLat = 15.3647
        val hubballiUserLon = 75.1240

        val delhiAirport = SearchLocation(
            id = "delhi_del",
            name = "Indira Gandhi International Airport",
            address = "New Delhi, South West Delhi, Delhi, 110037, India",
            latitude = 28.5562,
            longitude = 77.1000,
            placeType = "aerodrome",
            importance = 0.8
        )

        assertTrue(
            "Query 'delhi airport' contains explicit geographic qualifier for Delhi address",
            PlaceResultRanker.hasExplicitGeographicQualifier(delhiAirport, "delhi airport")
        )

        assertTrue(
            "Explicit distant destination search ('delhi airport') should qualify as STRONG",
            PlaceResultRanker.isStrongResult(
                delhiAirport,
                "delhi airport",
                hubballiUserLat,
                hubballiUserLon
            )
        )
    }

    @Test
    fun testStrongResult_F_GokulRoadFromHubballi_HubballiPreferredOverSouthAfrica() {
        val hubballiUserLat = 15.3647
        val hubballiUserLon = 75.1240

        val saGokulRoad = SearchLocation(
            id = "sa_gokul",
            name = "Gokul Road",
            address = "Umlazi, South Africa",
            latitude = -29.9702,
            longitude = 30.8845
        )

        val hubballiGokulRoad = SearchLocation(
            id = "hubli_gokul",
            name = "Gokul Road",
            address = "Gokul Industrial Estate, Hubballi, Karnataka, 580030, India",
            latitude = 15.3650,
            longitude = 75.1150
        )

        val ranked = PlaceResultRanker.rankResults(
            listOf(saGokulRoad, hubballiGokulRoad),
            "gokul road",
            hubballiUserLat,
            hubballiUserLon
        )

        assertEquals("Hubballi result must be ranked #1", "hubli_gokul", ranked.first().id)
    }

    @Test
    fun testStrongResult_G_KimsHospitalFromHubballi_HubballiPreferredOverMaidstone() {
        val hubballiUserLat = 15.3647
        val hubballiUserLon = 75.1240

        val maidstoneKims = SearchLocation(
            id = "maidstone_kims",
            name = "KIMS Hospital",
            address = "Newnham Court Way, Weavering, Maidstone, Kent, England, ME14 5FT, United Kingdom",
            latitude = 51.2783,
            longitude = 0.5593,
            placeType = "hospital",
            importance = 0.4
        )

        val hubballiKims = SearchLocation(
            id = "hubballi_kims",
            name = "KIMS Hospital",
            address = "Karnataka Institute of Medical Sciences, Vidyanagar, Hubballi, Karnataka, 580021, India",
            latitude = 15.3582,
            longitude = 75.1293,
            placeType = "hospital",
            importance = 0.4
        )

        val ranked = PlaceResultRanker.rankResults(
            listOf(maidstoneKims, hubballiKims),
            "kims hospital",
            hubballiUserLat,
            hubballiUserLon
        )

        assertEquals("Hubballi KIMS Hospital must be ranked #1", "hubballi_kims", ranked.first().id)
    }

    // =========================================================================
    // SECTION 14: SEARCH CANCELLATION / STALE LOCATION TESTS
    // =========================================================================

    @Test
    fun testSearchCancellation_LateResponseCannotOverwriteNewQuery() = runTest(testDispatcher) {
        val resultB = listOf(
            SearchLocation(id = "res_b", name = "New Loc", address = "New Addr", latitude = 15.0, longitude = 75.0)
        )

        fakeLocationRepo.searchResultToReturn = LocationSearchResult.Success(resultB)

        viewModel.setAcquiredPhoneLocation(RoutePoint(latitude = 10.0, longitude = 20.0, name = "Old Location"))
        viewModel.searchLocations("Query A", debounceMs = 200L)

        // Immediately launch Query B with new location before A executes
        viewModel.setAcquiredPhoneLocation(RoutePoint(latitude = 15.3647, longitude = 75.1240, name = "New Location"))
        viewModel.searchLocations("Query B", debounceMs = 0L)
        advanceUntilIdle()

        assertEquals("Query B", fakeLocationRepo.lastQuery)
        assertEquals(15.3647, fakeLocationRepo.lastLat!!, 0.0001)
        assertEquals(75.1240, fakeLocationRepo.lastLon!!, 0.0001)
        assertEquals("New Loc", viewModel.locationSearchResults.value.first().name)
    }

    @Test
    fun testLocationRefresh_DoesNotTriggerOldSearchResult() = runTest(testDispatcher) {
        viewModel.setAcquiredPhoneLocation(RoutePoint(latitude = 15.3647, longitude = 75.1240, name = "Hubballi"))
        fakeLocationRepo.searchCallCount = 0

        // Refresh location without search query
        fakeLocationProvider.resultToReturn = LocationResult.Success(15.4589, 75.0078)
        viewModel.refreshPhoneLocationForSearch(hasPermission = true)
        advanceUntilIdle()

        assertEquals("Location refresh alone must NOT trigger a search request", 0, fakeLocationRepo.searchCallCount)
    }
}
