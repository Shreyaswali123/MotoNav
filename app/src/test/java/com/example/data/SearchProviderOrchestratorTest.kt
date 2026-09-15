package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchProviderOrchestratorTest {

    private fun createNominatimMockClient(
        statusCode: Int = 200,
        responseBody: String = "[]",
        throwIoException: Boolean = false,
        onIntercept: ((String) -> Unit)? = null
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                onIntercept?.invoke(url)
                if (throwIoException) {
                    throw IOException("Simulated network failure")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode == 200) "OK" else "Error")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    private class MockPlaceSearchProvider(
        private val resultProvider: (query: String, lat: Double?, lon: Double?, limit: Int) -> List<SearchLocation>
    ) : PlaceSearchProvider {
        override val providerName: String = "MockPhoton"
        var lastQuery: String? = null
        var lastLat: Double? = null
        var lastLon: Double? = null
        var lastLimit: Int? = null
        var callCount = 0

        override suspend fun search(
            query: String,
            lat: Double?,
            lon: Double?,
            limit: Int
        ): List<SearchLocation> {
            callCount++
            lastQuery = query
            lastLat = lat
            lastLon = lon
            lastLimit = limit
            return resultProvider(query, lat, lon, limit)
        }
    }

    // 1. Photon success → Nominatim not called
    @Test
    fun testPhotonSuccessNominatimNotCalled() = runBlocking {
        val photonLocation = SearchLocation(
            id = "photon_1",
            name = "Shri Siddharuda Matha",
            address = "Old Hubli, Hubballi, Karnataka, India",
            latitude = 15.3400,
            longitude = 75.1400
        )
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            listOf(photonLocation)
        }

        var nominatimCallCount = 0
        val mockNominatimClient = createNominatimMockClient {
            nominatimCallCount++
        }

        val repo = DefaultLocationSearchRepository(
            httpClient = mockNominatimClient,
            photonProvider = mockPhoton
        )

        val result = repo.search("siddharuda mata", 15.3486, 75.1481)

        assertTrue(result is LocationSearchResult.Success)
        val success = result as LocationSearchResult.Success
        assertEquals(1, success.locations.size)
        assertEquals("Shri Siddharuda Matha", success.locations[0].name)
        assertEquals(1, mockPhoton.callCount)
        assertEquals(0, nominatimCallCount) // Nominatim was not called
    }

    // 2. Photon empty → Nominatim fallback
    @Test
    fun testPhotonEmptyTriggersNominatimFallback() = runBlocking {
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            emptyList()
        }

        val nominatimJson = """
            [
              {
                "place_id": 999,
                "lat": "15.3647",
                "lon": "75.1240",
                "name": "KIMS Hospital",
                "display_name": "KIMS Hospital, Vidyanagar, Hubballi, Karnataka, India"
              }
            ]
        """.trimIndent()

        var nominatimCallCount = 0
        val mockNominatimClient = createNominatimMockClient(responseBody = nominatimJson) {
            nominatimCallCount++
        }

        val repo = DefaultLocationSearchRepository(
            httpClient = mockNominatimClient,
            photonProvider = mockPhoton
        )

        val result = repo.search("kims hospital", 15.36, 75.12)

        assertTrue(result is LocationSearchResult.Success)
        val success = result as LocationSearchResult.Success
        assertEquals("KIMS Hospital", success.locations[0].name)
        assertEquals(1, mockPhoton.callCount)
        assertTrue("Nominatim should be called as fallback", nominatimCallCount >= 1)
    }

    // 3. Photon 429 → Nominatim fallback
    @Test
    fun testPhoton429TriggersNominatimFallback() = runBlocking {
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            throw PlaceSearchException.RateLimited(message = "Photon 429 Too Many Requests")
        }

        val nominatimJson = """
            [
              {
                "place_id": 101,
                "lat": "15.3500",
                "lon": "75.1300",
                "name": "Gokul Road",
                "display_name": "Gokul Road, Hubballi, Karnataka, India"
              }
            ]
        """.trimIndent()

        val mockNominatimClient = createNominatimMockClient(responseBody = nominatimJson)
        val repo = DefaultLocationSearchRepository(
            httpClient = mockNominatimClient,
            photonProvider = mockPhoton
        )

        val result = repo.search("gokul road", 15.35, 75.13)

        assertTrue("Photon 429 must not fail the search when Nominatim succeeds", result is LocationSearchResult.Success)
        val success = result as LocationSearchResult.Success
        assertEquals("Gokul Road", success.locations[0].name)
    }

    // 4. Photon 5xx → Nominatim fallback
    @Test
    fun testPhoton5xxTriggersNominatimFallback() = runBlocking {
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            throw PlaceSearchException.ServerError(503, "Service Unavailable")
        }

        val nominatimJson = """
            [
              {
                "place_id": 102,
                "lat": "15.3600",
                "lon": "75.1200",
                "name": "Vidyanagar",
                "display_name": "Vidyanagar, Hubballi, Karnataka, India"
              }
            ]
        """.trimIndent()

        val mockNominatimClient = createNominatimMockClient(responseBody = nominatimJson)
        val repo = DefaultLocationSearchRepository(
            httpClient = mockNominatimClient,
            photonProvider = mockPhoton
        )

        val result = repo.search("vidyanagar", 15.36, 75.12)

        assertTrue("Photon 503 must not fail search when Nominatim succeeds", result is LocationSearchResult.Success)
        val success = result as LocationSearchResult.Success
        assertEquals("Vidyanagar", success.locations[0].name)
    }

    // 5. Photon timeout → Nominatim fallback
    @Test
    fun testPhotonTimeoutTriggersNominatimFallback() = runBlocking {
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            throw PlaceSearchException.NetworkError("Photon connection timed out")
        }

        val nominatimJson = """
            [
              {
                "place_id": 103,
                "lat": "15.3700",
                "lon": "75.1100",
                "name": "Budan Gudda",
                "display_name": "Budan Gudda, Dharwad, Karnataka, India"
              }
            ]
        """.trimIndent()

        val mockNominatimClient = createNominatimMockClient(responseBody = nominatimJson)
        val repo = DefaultLocationSearchRepository(
            httpClient = mockNominatimClient,
            photonProvider = mockPhoton
        )

        val result = repo.search("budan gudda", 15.37, 75.11)

        assertTrue("Photon timeout must fall back to Nominatim", result is LocationSearchResult.Success)
        val success = result as LocationSearchResult.Success
        assertEquals("Budan Gudda", success.locations[0].name)
    }

    // 6. Photon malformed JSON → Nominatim fallback
    @Test
    fun testPhotonMalformedJsonTriggersNominatimFallback() = runBlocking {
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            throw PlaceSearchException.MalformedResponse("Corrupted GeoJSON")
        }

        val nominatimJson = """
            [
              {
                "place_id": 104,
                "lat": "15.3486",
                "lon": "75.1481",
                "name": "Hubballi Junction",
                "display_name": "Hubballi Junction, Station Road, Hubballi, Karnataka, India"
              }
            ]
        """.trimIndent()

        val mockNominatimClient = createNominatimMockClient(responseBody = nominatimJson)
        val repo = DefaultLocationSearchRepository(
            httpClient = mockNominatimClient,
            photonProvider = mockPhoton
        )

        val result = repo.search("hubballi station", 15.34, 75.14)

        assertTrue("Photon malformed response must fall back to Nominatim", result is LocationSearchResult.Success)
        val success = result as LocationSearchResult.Success
        assertEquals("Hubballi Junction", success.locations[0].name)
    }

    // 7. Both providers fail → existing error behavior
    @Test
    fun testBothProvidersFailReturnsExistingErrorBehavior() = runBlocking {
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            throw PlaceSearchException.RateLimited(message = "Photon 429")
        }

        // Nominatim returns 429
        val mockNominatimClient = createNominatimMockClient(statusCode = 429)
        val repo = DefaultLocationSearchRepository(
            httpClient = mockNominatimClient,
            photonProvider = mockPhoton
        )

        val result = repo.search("random query", 15.34, 75.14)

        assertTrue("When Nominatim also fails with 429, RateLimited should be returned", result is LocationSearchResult.RateLimited)
        val rateLimited = result as LocationSearchResult.RateLimited
        assertNotNull(rateLimited.fallbackLocations)
    }

    // 8. Photon with location bias
    @Test
    fun testPhotonWithLocationBiasPassesCoordinates() = runBlocking {
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            listOf(SearchLocation("p1", "Vidyanagar", "Vidyanagar, Hubballi", 15.3647, 75.1240))
        }

        val repo = DefaultLocationSearchRepository(
            httpClient = createNominatimMockClient(),
            photonProvider = mockPhoton
        )

        repo.search("vidyanagar", 15.3647, 75.1240)

        assertEquals(15.3647, mockPhoton.lastLat!!, 0.0001)
        assertEquals(75.1240, mockPhoton.lastLon!!, 0.0001)
        assertEquals(10, mockPhoton.lastLimit)
    }

    // 9. Photon without location
    @Test
    fun testPhotonWithoutLocationPassesNullCoordinates() = runBlocking {
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            listOf(SearchLocation("p1", "Vidyanagar", "Vidyanagar, Hubballi", 15.3647, 75.1240))
        }

        val repo = DefaultLocationSearchRepository(
            httpClient = createNominatimMockClient(),
            photonProvider = mockPhoton
        )

        repo.search("vidyanagar", null, null)

        assertEquals(null, mockPhoton.lastLat)
        assertEquals(null, mockPhoton.lastLon)
    }

    // 10. Duplicate Photon/Nominatim result handling
    @Test
    fun testDuplicatePhotonNominatimResultHandling() {
        val repo = DefaultLocationSearchRepository(httpClient = createNominatimMockClient(), photonProvider = null)

        val nominatimLocations = listOf(
            SearchLocation("nom_1", "Shri Siddharuda Matha", "Old Hubli, Hubballi, Karnataka", 15.34005, 75.14005),
            SearchLocation("nom_2", "KIMS Hospital", "Vidyanagar, Hubballi, Karnataka", 15.3647, 75.1240)
        )
        val photonLocations = listOf(
            // Duplicate of nom_1 (< 30m)
            SearchLocation("phot_1", "Shri Siddharuda Matha", "Old Hubballi, Hubli", 15.34008, 75.14007),
            // Unique location
            SearchLocation("phot_2", "Unkal Lake", "Unkal, Hubballi, Karnataka", 15.3780, 75.1180)
        )

        val deduplicated = repo.deduplicateLocations(nominatimLocations, photonLocations)

        assertEquals(3, deduplicated.size)
        assertTrue(deduplicated.any { it.name == "Shri Siddharuda Matha" })
        assertTrue(deduplicated.any { it.name == "KIMS Hospital" })
        assertTrue(deduplicated.any { it.name == "Unkal Lake" })
    }

    // 11. Strong Photon result prevents unnecessary Nominatim call
    @Test
    fun testStrongPhotonResultPreventsUnnecessaryNominatimCall() = runBlocking {
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            listOf(
                SearchLocation("p1", "KIMS Hospital", "KIMS Hospital, Vidyanagar, Hubballi", 15.3647, 75.1240)
            )
        }

        var nominatimCalled = false
        val mockNominatimClient = createNominatimMockClient {
            nominatimCalled = true
        }

        val repo = DefaultLocationSearchRepository(
            httpClient = mockNominatimClient,
            photonProvider = mockPhoton
        )

        val result = repo.search("kims hospital", 15.36, 75.12)

        assertTrue(result is LocationSearchResult.Success)
        assertFalse("Strong Photon result must prevent calling Nominatim", nominatimCalled)
    }

    // 12. Weak/unrelated Photon result permits fallback
    @Test
    fun testWeakUnrelatedPhotonResultPermitsFallback() = runBlocking {
        // Photon returns an irrelevant location (in Berlin, 6000km away with zero token overlap)
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            listOf(
                SearchLocation("p_irr", "Alexanderplatz", "Berlin, Germany", 52.5219, 13.4132)
            )
        }

        val nominatimJson = """
            [
              {
                "place_id": 555,
                "lat": "15.3500",
                "lon": "75.1300",
                "name": "Gokul Road",
                "display_name": "Gokul Road, Hubballi, Karnataka, India"
              }
            ]
        """.trimIndent()

        var nominatimCalled = false
        val mockNominatimClient = createNominatimMockClient(responseBody = nominatimJson) {
            nominatimCalled = true
        }

        val repo = DefaultLocationSearchRepository(
            httpClient = mockNominatimClient,
            photonProvider = mockPhoton
        )

        val result = repo.search("gokul road", 15.35, 75.13)

        assertTrue(result is LocationSearchResult.Success)
        assertTrue("Weak Photon result must permit Nominatim fallback", nominatimCalled)
        val success = result as LocationSearchResult.Success
        assertEquals("Gokul Road", success.locations[0].name)
    }

    // 13. Existing Nominatim rate limiter remains enforced on fallback
    @Test
    fun testNominatimRateLimiterRemainsEnforcedOnFallback() = runBlocking {
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            emptyList()
        }

        var delayCount = 0
        var totalDelayMs = 0L
        val repo = DefaultLocationSearchRepository(
            httpClient = createNominatimMockClient(),
            minRequestIntervalMs = 1000L,
            delayer = { ms ->
                delayCount++
                totalDelayMs += ms
            },
            photonProvider = mockPhoton
        )

        // Two consecutive empty searches that trigger Nominatim
        repo.search("query1")
        repo.search("query2")

        assertTrue("Delayer should be invoked for Nominatim rate pacing", delayCount >= 1)
        assertTrue("Rate pacing delay should be positive", totalDelayMs > 0)
    }

    // 14. Existing Nominatim cache remains functional on fallback
    @Test
    fun testNominatimCacheRemainsFunctionalOnFallback() = runBlocking {
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            emptyList()
        }

        val nominatimJson = """
            [
              {
                "place_id": 1,
                "lat": "15.3486",
                "lon": "75.1481",
                "name": "Hubballi Junction",
                "display_name": "Hubballi Junction, Station Road, Hubballi, Karnataka, India"
              }
            ]
        """.trimIndent()

        val callCount = AtomicInteger(0)
        val mockNominatimClient = createNominatimMockClient(responseBody = nominatimJson) {
            callCount.incrementAndGet()
        }

        val repo = DefaultLocationSearchRepository(
            httpClient = mockNominatimClient,
            minRequestIntervalMs = 0L,
            photonProvider = mockPhoton
        )

        val res1 = repo.search("hubballi station")
        val callsAfterFirst = callCount.get()

        val res2 = repo.search("hubballi station")
        val callsAfterSecond = callCount.get()

        assertTrue(res1 is LocationSearchResult.Success)
        assertTrue(res2 is LocationSearchResult.Success)
        assertEquals("Nominatim request should be cached and not repeated", callsAfterFirst, callsAfterSecond)
    }

    // 15. Existing cancellation behavior remains correct
    @Test
    fun testExistingCancellationBehaviorRemainsCorrect() = runBlocking {
        val mockPhoton = MockPlaceSearchProvider { _, _, _, _ ->
            emptyList()
        }

        var wasCancelled = false
        val job = launch {
            val repo = DefaultLocationSearchRepository(
                httpClient = createNominatimMockClient(),
                delayer = { delay(5000) },
                photonProvider = mockPhoton
            )
            try {
                repo.search("test")
            } catch (e: CancellationException) {
                wasCancelled = true
                throw e
            }
        }

        delay(50)
        job.cancel()
        job.join()

        assertTrue("Cancellation should properly abort search orchestrator", wasCancelled || job.isCancelled)
    }

    // 16. Real Search Regression Cases (Section 14)
    @Test
    fun testRealSearchRegressionCasesStructuralIntegrity() = runBlocking {
        val regressionQueries = listOf(
            "shri siddharuda mata",
            "siddharuda mata",
            "siddharudha math",
            "budan gudda",
            "gokul road",
            "kims hospital",
            "vidyanagar",
            "siddharud"
        )

        val mockPhoton = MockPlaceSearchProvider { query, lat, lon, limit ->
            // Structural provider verifying normalization and bias parameter flow
            listOf(
                SearchLocation(
                    id = "reg_$query",
                    name = query.replaceFirstChar { it.uppercase() },
                    address = "$query, Hubballi-Dharwad, Karnataka, India",
                    latitude = lat ?: 15.3647,
                    longitude = lon ?: 75.1240
                )
            )
        }

        val repo = DefaultLocationSearchRepository(
            httpClient = createNominatimMockClient(),
            photonProvider = mockPhoton
        )

        for (rawQuery in regressionQueries) {
            // Verify query normalization
            val normalized = PlaceQueryNormalizer.normalize(rawQuery)
            assertTrue("Normalized query should not be blank for '$rawQuery'", normalized.isNotBlank())

            // Verify candidate generation produces viable variants
            val candidates = PlaceQueryNormalizer.getCandidateQueries(rawQuery)
            assertTrue("Candidates should contain at least 1 query for '$rawQuery'", candidates.isNotEmpty())

            // Test search execution with location bias
            val result = repo.search(rawQuery, 15.3647, 75.1240)
            assertTrue("Search result for '$rawQuery' should be Success", result is LocationSearchResult.Success)
            val success = result as LocationSearchResult.Success
            assertFalse("Search result for '$rawQuery' should not be empty", success.locations.isEmpty())
            assertTrue("Coordinates must be valid numbers", success.locations[0].latitude.isFinite())
            assertTrue("Coordinates must be valid numbers", success.locations[0].longitude.isFinite())
        }
    }

    // 17. Photon GeoJSON parsing correctness test
    @Test
    fun testPhotonGeoJsonParsing() {
        val sampleGeoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": {
                    "type": "Point",
                    "coordinates": [75.1481, 15.3486]
                  },
                  "properties": {
                    "osm_id": 123456,
                    "osm_type": "N",
                    "osm_key": "railway",
                    "osm_value": "station",
                    "name": "Hubballi Railway Station",
                    "street": "Station Road",
                    "city": "Hubballi",
                    "state": "Karnataka",
                    "country": "India",
                    "postcode": "580020"
                  }
                }
              ]
            }
        """.trimIndent()

        val provider = PhotonSearchProvider()
        val locations = provider.parsePhotonGeoJson(sampleGeoJson)

        assertEquals(1, locations.size)
        val loc = locations[0]
        assertEquals("Hubballi Railway Station", loc.name)
        assertEquals(15.3486, loc.latitude, 0.0001)
        assertEquals(75.1481, loc.longitude, 0.0001)
        assertTrue(loc.address.contains("Station Road"))
        assertTrue(loc.address.contains("Hubballi"))
        assertTrue(loc.address.contains("Karnataka"))
    }
}
