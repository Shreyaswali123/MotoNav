package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocationSearchRepositoryTest {

    private fun createMockClient(
        statusCode: Int = 200,
        responseBody: String = "[]",
        throwIoException: Boolean = false
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (throwIoException) {
                    throw IOException("Simulated network failure")
                }
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode == 200) "OK" else "Error")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    @Test
    fun testSearchKnownPlaceReturnsResults() = runBlocking {
        // Requirement 13A: Search for a known place returns results
        val mockJson = """
            [
              {
                "place_id": 123456,
                "lat": "15.3486",
                "lon": "75.1481",
                "name": "Hubballi Railway Station",
                "display_name": "Hubballi Junction, Station Road, Hubballi, Dharwad, Karnataka, 580020, India"
              }
            ]
        """.trimIndent()

        val repo = DefaultLocationSearchRepository(
            httpClient = createMockClient(responseBody = mockJson)
        )

        val result = repo.search("Hubballi Railway Station")
        assertTrue("Result should be Success", result is LocationSearchResult.Success)
        val success = result as LocationSearchResult.Success
        assertEquals(1, success.locations.size)
        assertEquals("Hubballi Railway Station", success.locations[0].name)
    }

    @Test
    fun testSearchResultMapsCorrectlyToSearchLocation() = runBlocking {
        // Requirement 13B: Search result maps correctly to SearchLocation
        val mockJson = """
            [
              {
                "place_id": 987654,
                "lat": "15.3690067",
                "lon": "75.1236571",
                "name": "KLE Technological University",
                "display_name": "KLE Technological University, Vidyanagar, Hubballi, Karnataka, 580031, India"
              }
            ]
        """.trimIndent()

        val repo = DefaultLocationSearchRepository(
            httpClient = createMockClient(responseBody = mockJson)
        )

        val result = repo.search("KLE")
        assertTrue(result is LocationSearchResult.Success)
        val location = (result as LocationSearchResult.Success).locations[0]

        assertEquals("osm_987654", location.id)
        assertEquals("KLE Technological University", location.name)
        assertEquals("KLE Technological University, Vidyanagar, Hubballi, Karnataka, 580031, India", location.address)
        assertTrue(location.isValid())
    }

    @Test
    fun testLatitudeLongitudePreservedCorrectly() = runBlocking {
        // Requirement 13C: Latitude/longitude are preserved correctly
        val expectedLat = 15.3647
        val expectedLon = 75.1240

        val mockJson = """
            [
              {
                "place_id": 112233,
                "lat": "15.3647",
                "lon": "75.1240",
                "display_name": "Custom Place, Hubballi"
              }
            ]
        """.trimIndent()

        val repo = DefaultLocationSearchRepository(
            httpClient = createMockClient(responseBody = mockJson)
        )

        val result = repo.search("Custom Place")
        assertTrue(result is LocationSearchResult.Success)
        val location = (result as LocationSearchResult.Success).locations[0]

        assertEquals(expectedLat, location.latitude, 0.000001)
        assertEquals(expectedLon, location.longitude, 0.000001)
    }

    @Test
    fun testEmptyResultHandledCorrectly() = runBlocking {
        // Requirement 13D: Empty result is handled correctly
        val repo = DefaultLocationSearchRepository(
            httpClient = createMockClient(responseBody = "[]")
        )

        val result = repo.search("non_existent_place_query_12345")
        assertTrue("Result should be Empty, not Error", result is LocationSearchResult.Empty)
        val empty = result as LocationSearchResult.Empty
        assertEquals("non_existent_place_query_12345", empty.query)
    }

    @Test
    fun testNetworkFailureHandledSeparatelyFromEmptyResults() = runBlocking {
        // Requirement 13E: Network failure is handled separately from empty results
        val repo = DefaultLocationSearchRepository(
            httpClient = createMockClient(throwIoException = true)
        )

        val result = repo.search("arbitrary place")
        assertTrue("Network error must return NetworkError result", result is LocationSearchResult.NetworkError)
        val networkError = result as LocationSearchResult.NetworkError
        assertEquals("Unable to search locations. Check your internet connection.", networkError.message)
    }

    @Test
    fun testHttpErrorHandledAsNetworkFailure() = runBlocking {
        val repo = DefaultLocationSearchRepository(
            httpClient = createMockClient(statusCode = 503, responseBody = "Service Unavailable")
        )

        val result = repo.search("arbitrary place")
        assertTrue(result is LocationSearchResult.NetworkError)
        val networkError = result as LocationSearchResult.NetworkError
        assertEquals("Unable to search locations. Check your internet connection.", networkError.message)
    }

    @Test
    fun testMalformedResponseHandledAsMalformedResponse() = runBlocking {
        val repo = DefaultLocationSearchRepository(
            httpClient = createMockClient(responseBody = "{ not valid json array }")
        )

        val result = repo.search("test")
        assertTrue(result is LocationSearchResult.MalformedResponse)
    }

    @Test
    fun testExistingPopularLocationsStillWork() = runBlocking {
        // Requirement 13H: Existing popular locations still work
        val repo = DefaultLocationSearchRepository()
        val popular = repo.getPopularLocations()

        assertTrue("Popular locations must not be empty", popular.isNotEmpty())
        assertTrue("Must contain KLE Tech", popular.any { it.name.contains("KLE", ignoreCase = true) })
        assertTrue("Must contain TolanKere", popular.any { it.name.contains("Tolan", ignoreCase = true) })

        // When query is empty string, search returns popular locations
        val emptyQueryRes = repo.search("")
        assertTrue(emptyQueryRes is LocationSearchResult.Success)
        assertEquals(popular.size, (emptyQueryRes as LocationSearchResult.Success).locations.size)
    }

    @Test
    fun testMalformedCoordinatesSafelyRejected() = runBlocking {
        // Requirement 11: Reject malformed results safely
        val mockJson = """
            [
              {
                "place_id": 1,
                "lat": "NaN",
                "lon": "75.0",
                "display_name": "Invalid Lat NaN"
              },
              {
                "place_id": 2,
                "lat": "95.0",
                "lon": "75.0",
                "display_name": "Lat > 90"
              },
              {
                "place_id": 3,
                "lat": "15.0",
                "lon": "190.0",
                "display_name": "Lon > 180"
              },
              {
                "place_id": 4,
                "lat": "15.3486",
                "lon": "75.1481",
                "display_name": "Valid Place, Hubballi"
              }
            ]
        """.trimIndent()

        val repo = DefaultLocationSearchRepository(
            httpClient = createMockClient(responseBody = mockJson)
        )

        val result = repo.search("test")
        assertTrue(result is LocationSearchResult.Success)
        val locations = (result as LocationSearchResult.Success).locations
        assertEquals("Only valid coordinates must be returned", 1, locations.size)
        assertEquals("Valid Place", locations[0].name)
    }

    @Test
    fun testSearchLocationIsValid() {
        val valid = SearchLocation("1", "Test", "Addr", 15.0, 75.0)
        assertTrue(valid.isValid())

        val invalidLat = SearchLocation("2", "Test", "Addr", 91.0, 75.0)
        assertFalse(invalidLat.isValid())

        val invalidLon = SearchLocation("3", "Test", "Addr", 15.0, -181.0)
        assertFalse(invalidLon.isValid())

        val nanLat = SearchLocation("4", "Test", "Addr", Double.NaN, 75.0)
        assertFalse(nanLat.isValid())
    }

    @Test
    fun testKleTechFindsMockedKleTechnologicalUniversity() = runBlocking {
        // "KLE Tech" finding a mocked Nominatim result named "KLE Technological University"
        val mockJson = """
            [
              {
                "place_id": 987654,
                "lat": "15.3690067",
                "lon": "75.1236571",
                "name": "KLE Technological University",
                "display_name": "KLE Technological University, Vidyanagar, Hubballi, Karnataka, 580031, India",
                "type": "university",
                "importance": 0.75
              }
            ]
        """.trimIndent()

        val repo = DefaultLocationSearchRepository(httpClient = createMockClient(responseBody = mockJson))
        val result = repo.search("KLE Tech")

        assertTrue("Searching KLE Tech should succeed", result is LocationSearchResult.Success)
        val locations = (result as LocationSearchResult.Success).locations
        assertEquals(1, locations.size)
        assertEquals("KLE Technological University", locations[0].name)
    }

    @Test
    fun testCaseDifferencesDoNotAffectSearchResults() = runBlocking {
        val mockJson = """
            [
              {
                "place_id": 101,
                "lat": "15.3690",
                "lon": "75.1236",
                "name": "KLE Technological University",
                "display_name": "Vidyanagar, Hubballi"
              }
            ]
        """.trimIndent()

        val repo = DefaultLocationSearchRepository(httpClient = createMockClient(responseBody = mockJson))
        val resultLower = repo.search("kle tech")
        val resultUpper = repo.search("KLE TECH")
        val resultMixed = repo.search("kLe TeCh")

        assertTrue(resultLower is LocationSearchResult.Success)
        assertTrue(resultUpper is LocationSearchResult.Success)
        assertTrue(resultMixed is LocationSearchResult.Success)

        assertEquals("KLE Technological University", (resultLower as LocationSearchResult.Success).locations[0].name)
        assertEquals("KLE Technological University", (resultUpper as LocationSearchResult.Success).locations[0].name)
        assertEquals("KLE Technological University", (resultMixed as LocationSearchResult.Success).locations[0].name)
    }

    @Test
    fun testExtraSpacesNormalizedInSearch() = runBlocking {
        var capturedQuery: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedQuery = chain.request().url.queryParameter("q")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""[{"place_id":1,"lat":"15.369","lon":"75.123","name":"KLE Technological University","display_name":"Vidyanagar"}]""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client)
        val result = repo.search("   KLE     Technological    University   ")

        assertTrue(result is LocationSearchResult.Success)
        assertEquals("KLE Technological University", capturedQuery)
    }

    @Test
    fun testPunctuationDifferencesToleratedInSearch() = runBlocking {
        var capturedQuery: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedQuery = chain.request().url.queryParameter("q")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""[{"place_id":1,"lat":"15.369","lon":"75.123","name":"KLE Tech","display_name":"KLE Tech, Hubballi"}]""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client)
        repo.search("K.L.E. Tech! Hubballi?")

        assertNotNull(capturedQuery)
        assertFalse("Query should not contain exclamation marks", capturedQuery!!.contains("!"))
        assertFalse("Query should not contain question marks", capturedQuery!!.contains("?"))
    }

    @Test
    fun testTolanKereVsTolanKereCompoundHandled() = runBlocking {
        val mockJson = """
            [
              {
                "place_id": 555,
                "lat": "15.3582",
                "lon": "75.1032",
                "name": "Tolankere Lake",
                "display_name": "Tolan Kere, Vivekanand Nagar, Hubballi",
                "type": "lake",
                "importance": 0.6
              }
            ]
        """.trimIndent()

        val repo = DefaultLocationSearchRepository(httpClient = createMockClient(responseBody = mockJson))
        val resCompound = repo.search("TolanKere")
        val resSeparated = repo.search("Tolan Kere")

        assertTrue(resCompound is LocationSearchResult.Success)
        assertTrue(resSeparated is LocationSearchResult.Success)
        assertEquals("Tolankere Lake", (resCompound as LocationSearchResult.Success).locations[0].name)
        assertEquals("Tolankere Lake", (resSeparated as LocationSearchResult.Success).locations[0].name)
    }

    @Test
    fun testMultipleResultsPreservedAndRanked() = runBlocking {
        val mockJson = """
            [
              {
                "place_id": 1,
                "lat": "15.3486",
                "lon": "75.1481",
                "name": "Hubballi Railway Station",
                "display_name": "Hubballi Junction, Station Road, Hubballi",
                "type": "station",
                "importance": 0.8
              },
              {
                "place_id": 2,
                "lat": "15.3500",
                "lon": "75.1500",
                "name": "Station Cafe",
                "display_name": "Station Cafe, Hubballi",
                "type": "cafe",
                "importance": 0.2
              },
              {
                "place_id": 3,
                "lat": "15.4589",
                "lon": "75.0078",
                "name": "Dharwad Railway Station",
                "display_name": "Dharwad Station, Dharwad",
                "type": "station",
                "importance": 0.6
              }
            ]
        """.trimIndent()

        val repo = DefaultLocationSearchRepository(httpClient = createMockClient(responseBody = mockJson))
        val result = repo.search("Hubballi Station")

        assertTrue(result is LocationSearchResult.Success)
        val locations = (result as LocationSearchResult.Success).locations
        assertEquals("All returned candidates should be preserved", 3, locations.size)
        assertEquals("Hubballi Railway Station should be ranked first", "Hubballi Railway Station", locations[0].name)
    }

    @Test
    fun testIndiaBiasedSearchParametersInRequest() = runBlocking {
        val capturedUrls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedUrls.add(chain.request().url.toString())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("[]".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client)
        repo.search("Hubballi")

        assertTrue("At least one request should be made", capturedUrls.isNotEmpty())
        assertTrue(
            "Initial search request should contain countrycodes=in parameter",
            capturedUrls.first().contains("countrycodes=in")
        )
    }

    @Test
    fun testGlobalFallbackWhenIndiaSearchReturnsEmpty() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val country = chain.request().url.queryParameter("countrycodes")
                val body = if (country == "in") {
                    "[]"
                } else {
                    """[{"place_id":77,"lat":"48.8584","lon":"2.2945","name":"Eiffel Tower","display_name":"Champ de Mars, Paris, France"}]"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client)
        val result = repo.search("Eiffel Tower")

        assertTrue(result is LocationSearchResult.Success)
        val locs = (result as LocationSearchResult.Success).locations
        assertEquals(1, locs.size)
        assertEquals("Eiffel Tower", locs[0].name)
    }

    @Test
    fun testHttpNon2xxResponseClosesResponseBody() = runBlocking {
        var bodyClosed = false
        val delegate = "Service Unavailable".toResponseBody("text/plain".toMediaType())
        val trackingBody = object : ResponseBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun source() = delegate.source()
            override fun close() {
                bodyClosed = true
                delegate.close()
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Service Unavailable")
                    .body(trackingBody)
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client)
        val result = repo.search("Hubballi")

        assertTrue("Non-2xx status code must produce NetworkError", result is LocationSearchResult.NetworkError)
        assertTrue("Response body must be closed on non-2xx HTTP responses", bodyClosed)
    }

    @Test
    fun testHttp429ResponseClosesResponseBody() = runBlocking {
        var bodyClosed = false
        val delegate = "Too Many Requests".toResponseBody("text/plain".toMediaType())
        val trackingBody = object : ResponseBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun source() = delegate.source()
            override fun close() {
                bodyClosed = true
                delegate.close()
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body(trackingBody)
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client)
        val result = repo.search("Hubballi")

        assertTrue("429 status code must produce RateLimited", result is LocationSearchResult.RateLimited)
        val rateLimited = result as LocationSearchResult.RateLimited
        assertEquals("Search service is temporarily busy. Please wait a moment and try again.", rateLimited.message)
        assertTrue("Response body must be closed on 429 Too Many Requests response", bodyClosed)
    }

    @Test
    fun testHttp200SuccessResponseClosesResponseBody() = runBlocking {
        var bodyClosed = false
        val mockJson = """[{"place_id":100,"lat":"15.3647","lon":"75.1240","name":"Hubballi","display_name":"Hubballi, Karnataka, India"}]"""
        val delegate = mockJson.toResponseBody("application/json".toMediaType())
        val trackingBody = object : ResponseBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun source() = delegate.source()
            override fun close() {
                bodyClosed = true
                delegate.close()
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(trackingBody)
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client)
        val result = repo.search("Hubballi")

        assertTrue("Valid response must produce Success", result is LocationSearchResult.Success)
        val success = result as LocationSearchResult.Success
        assertEquals(1, success.locations.size)
        assertEquals("Hubballi", success.locations[0].name)
        assertTrue("Response body must be closed on 200 OK responses", bodyClosed)
    }

    // --- Issue #9: Cancellation & In-Flight Request Abort Tests ---

    @Test
    fun testCancellationAbortsInFlightHttpCall() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val callCancelledLatch = CountDownLatch(1)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val call = chain.call()
                requestStarted.countDown()
                while (!call.isCanceled()) {
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                if (call.isCanceled()) {
                    callCancelledLatch.countDown()
                    throw IOException("Canceled")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("[]".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client)
        var caughtCancellation = false
        var searchResult: LocationSearchResult? = null

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                searchResult = repo.search("Hubballi")
            } catch (e: CancellationException) {
                caughtCancellation = true
            }
        }

        assertTrue("Request must start", requestStarted.await(3, TimeUnit.SECONDS))
        job.cancel()
        assertTrue("OkHttp Call must receive cancel() invocation", callCancelledLatch.await(3, TimeUnit.SECONDS))
        job.join()

        assertTrue("Coroutine must complete as CancellationException", caughtCancellation)
        assertEquals("searchResult must remain null and not return NetworkError", null, searchResult)
    }

    @Test
    fun testCancellationPreventsGlobalFallbackRequest() = runBlocking {
        val requestUrls = Collections.synchronizedList(mutableListOf<String>())
        val firstRequestStarted = CountDownLatch(1)
        val continueFirstResponse = CountDownLatch(1)
        var repoJob: Job? = null

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                requestUrls.add(url)
                firstRequestStarted.countDown()
                continueFirstResponse.await(3, TimeUnit.SECONDS)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("[]".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client)
        var caughtCancellation = false

        repoJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.search("Hubballi")
            } catch (e: CancellationException) {
                caughtCancellation = true
            }
        }

        assertTrue("First request must start", firstRequestStarted.await(3, TimeUnit.SECONDS))
        repoJob.cancel()
        continueFirstResponse.countDown()
        repoJob.join()

        assertTrue("Coroutine must complete as CancellationException", caughtCancellation)
        assertEquals("Exactly 1 request should be dispatched; global fallback must be aborted", 1, requestUrls.size)
        assertTrue("First request was country-biased", requestUrls[0].contains("countrycodes=in"))
    }

    @Test
    fun testCancellationPreventsAliasCandidateFallbackRequest() = runBlocking {
        val requestUrls = Collections.synchronizedList(mutableListOf<String>())
        val globalRequestStarted = CountDownLatch(1)
        val continueGlobalResponse = CountDownLatch(1)
        var repoJob: Job? = null

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                requestUrls.add(url)
                val isGlobal = !url.contains("countrycodes=")
                if (isGlobal) {
                    globalRequestStarted.countDown()
                    continueGlobalResponse.await(3, TimeUnit.SECONDS)
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("[]".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client)
        var caughtCancellation = false

        repoJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // "KLE" generates multiple alias/expansion candidate queries
                repo.search("KLE")
            } catch (e: CancellationException) {
                caughtCancellation = true
            }
        }

        assertTrue("Global request must start", globalRequestStarted.await(3, TimeUnit.SECONDS))
        repoJob.cancel()
        continueGlobalResponse.countDown()
        repoJob.join()

        assertTrue("Coroutine must complete as CancellationException", caughtCancellation)
        assertEquals("Exactly 2 requests (India and Global) should be dispatched; alias queries must be aborted", 2, requestUrls.size)
    }

    @Test
    fun testGenuineIoExceptionReturnsNetworkError() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("Connection reset by peer")
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client)
        val result = repo.search("Hubballi")

        assertTrue("Genuine network failure must produce NetworkError", result is LocationSearchResult.NetworkError)
        val networkError = result as LocationSearchResult.NetworkError
        assertEquals("Unable to search locations. Check your internet connection.", networkError.message)
    }

    // --- Issue #10A: Rate Limiting & Query Cache Tests ---

    @Test
    fun testFallbackRequestsSeparatedByMinimumRequestInterval() = runBlocking {
        // Requirement 9A: Consecutive outbound fallback requests separated by minimum request interval
        val requestTimestampsNanos = Collections.synchronizedList(mutableListOf<Long>())

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestTimestampsNanos.add(System.nanoTime())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("[]".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        // Use 250ms interval for deterministic, non-sluggish test execution
        val intervalMs = 250L
        val repo = DefaultLocationSearchRepository(
            httpClient = client,
            minRequestIntervalMs = intervalMs
        )

        // Searching for unknown place triggers India search -> empty -> Global search -> empty
        val result = repo.search("Unknown Place XYZ")
        assertTrue("Search should return Empty", result is LocationSearchResult.Empty)
        assertTrue("Should have dispatched at least 2 fallback requests", requestTimestampsNanos.size >= 2)

        for (i in 1 until requestTimestampsNanos.size) {
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(requestTimestampsNanos[i] - requestTimestampsNanos[i - 1])
            assertTrue(
                "Consecutive requests must be separated by >= ${intervalMs}ms (found ${elapsedMs}ms)",
                elapsedMs >= (intervalMs - 20) // small tolerance for thread scheduling
            )
        }
    }

    @Test
    fun testConcurrentRequestsPacedByRateLimiter() = runBlocking {
        // Requirement 9B: Two concurrent searches against the same repository do not violate minimum interval
        val requestTimestampsNanos = Collections.synchronizedList(mutableListOf<Long>())

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestTimestampsNanos.add(System.nanoTime())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""[{"place_id":1,"lat":"15.3","lon":"75.1","display_name":"Place"}]""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val intervalMs = 300L
        val repo = DefaultLocationSearchRepository(
            httpClient = client,
            minRequestIntervalMs = intervalMs
        )

        val job1 = launch(Dispatchers.IO) {
            repo.search("Place Alpha")
        }
        val job2 = launch(Dispatchers.IO) {
            repo.search("Place Beta")
        }

        job1.join()
        job2.join()

        assertEquals("Both searches should have dispatched 1 request", 2, requestTimestampsNanos.size)
        val elapsedMs = Math.abs(TimeUnit.NANOSECONDS.toMillis(requestTimestampsNanos[1] - requestTimestampsNanos[0]))
        assertTrue(
            "Concurrent requests must be separated by >= ${intervalMs}ms (found ${elapsedMs}ms)",
            elapsedMs >= (intervalMs - 25)
        )
    }

    @Test
    fun testCacheHitPreventsDuplicateHttpRequest() = runBlocking {
        // Requirement 9C: Search the same normalized query + countryCode twice; second search does not generate HTTP request
        val requestCount = java.util.concurrent.atomic.AtomicInteger(0)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""[{"place_id":1,"lat":"15.36","lon":"75.12","display_name":"Hubballi, Karnataka"}]""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client)

        val result1 = repo.search("Hubballi")
        assertTrue("First search returns Success", result1 is LocationSearchResult.Success)
        assertEquals("First search generates 1 HTTP request", 1, requestCount.get())

        // Same query with leading/trailing spaces and different casing
        val result2 = repo.search("   HUBBALLI   ")
        assertTrue("Second search returns Success", result2 is LocationSearchResult.Success)
        assertEquals("Second search must be served from cache without HTTP request", 1, requestCount.get())
        assertEquals(
            (result1 as LocationSearchResult.Success).locations.first().id,
            (result2 as LocationSearchResult.Success).locations.first().id
        )
    }

    @Test
    fun testCacheKeyCountryDistinction() = runBlocking {
        // Requirement 9D: CountryCode is part of cache key; country "in" vs null are separate cache entries
        val requestedUrls = Collections.synchronizedList(mutableListOf<String>())

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                requestedUrls.add(url)
                val isCountryIn = url.contains("countrycodes=in")
                val responseBody = if (isCountryIn) {
                    "[]" // empty for India
                } else {
                    """[{"place_id":42,"lat":"48.858","lon":"2.294","display_name":"Eiffel Tower, Paris"}]""" // success for global
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(
            httpClient = client,
            minRequestIntervalMs = 0L // no delay for cache functional test
        )

        // First search: "Eiffel Tower"
        // 1st request: India ("in") -> returns empty (cached as Empty under key ("eiffel tower", "in"))
        // 2nd request: Global (null) -> returns Success (cached as Success under key ("eiffel tower", null))
        val result1 = repo.search("Eiffel Tower")
        assertTrue("First search returns Success via global fallback", result1 is LocationSearchResult.Success)
        assertEquals("Two requests dispatched on first search (India + Global)", 2, requestedUrls.size)
        assertTrue(requestedUrls[0].contains("countrycodes=in"))
        assertFalse(requestedUrls[1].contains("countrycodes="))

        // Second search: "Eiffel Tower"
        // Should hit cache for ("eiffel tower", "in") -> Empty
        // Then hit cache for ("eiffel tower", null) -> Success
        val result2 = repo.search("Eiffel Tower")
        assertTrue("Second search returns Success from cache", result2 is LocationSearchResult.Success)
        assertEquals("No new requests dispatched on second search; both cache keys hit", 2, requestedUrls.size)
    }

    @Test
    fun testCacheEmptyResultPreventsSubsequentHttpRequests() = runBlocking {
        // Requirement 9E: Empty results are cached so repeated empty search does not generate another request
        val requestCount = java.util.concurrent.atomic.AtomicInteger(0)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("[]".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(
            httpClient = client,
            minRequestIntervalMs = 0L
        )

        val result1 = repo.search("NonexistentLandmarkXYZ")
        assertTrue(result1 is LocationSearchResult.Empty)
        val initialRequestCount = requestCount.get()
        assertTrue("Initial search made requests", initialRequestCount > 0)

        val result2 = repo.search("NonexistentLandmarkXYZ")
        assertTrue(result2 is LocationSearchResult.Empty)
        assertEquals("Second search must be served entirely from cache with 0 additional HTTP requests", initialRequestCount, requestCount.get())
    }

    @Test
    fun testNetworkErrorIsNotCached() = runBlocking {
        // Requirement 9F: NetworkError is not cached; retry is allowed to make another HTTP request
        val requestCount = java.util.concurrent.atomic.AtomicInteger(0)
        var failWithIoException = true

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                if (failWithIoException) {
                    throw IOException("Temporary network timeout")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""[{"place_id":1,"lat":"15.36","lon":"75.12","display_name":"Hubballi"}]""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(
            httpClient = client,
            minRequestIntervalMs = 0L
        )

        // First search fails
        val result1 = repo.search("Hubballi")
        assertTrue("First search returns NetworkError", result1 is LocationSearchResult.NetworkError)
        assertEquals("First search attempted 1 HTTP request", 1, requestCount.get())

        // Recover network
        failWithIoException = false

        // Second search should NOT return cached NetworkError; must retry
        val result2 = repo.search("Hubballi")
        assertTrue("Second search succeeds after network recovery", result2 is LocationSearchResult.Success)
        assertEquals("Second search dispatched a second HTTP request (retry allowed)", 2, requestCount.get())
    }

    @Test
    fun testCancellationWhileWaitingForRateLimiter() = runBlocking {
        // Requirement 9G: Cancellation while waiting for rate limiter aborts, does not dispatch HTTP request, propagates CancellationException
        val requestDispatched = java.util.concurrent.atomic.AtomicBoolean(false)
        val secondRequestAttempted = java.util.concurrent.atomic.AtomicBoolean(false)
        val limiterDelayEntered = CountDownLatch(1)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestDispatched.set(true)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""[{"place_id":1,"lat":"15.3","lon":"75.1","display_name":"First"}]""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        // Inject custom delayer that signals when the second request begins waiting for rate limiter
        val repo = DefaultLocationSearchRepository(
            httpClient = client,
            minRequestIntervalMs = 5000L, // long interval so second request must wait
            delayer = { delayMs ->
                limiterDelayEntered.countDown()
                delay(delayMs)
            }
        )

        // First search succeeds immediately (first dispatch)
        val result1 = repo.search("First Place")
        assertTrue("First search succeeds", result1 is LocationSearchResult.Success)

        var caughtCancellation = false
        val job = launch(Dispatchers.IO) {
            try {
                // Second search with distinct query will enter rate limiter delay
                repo.search("Second Place")
                secondRequestAttempted.set(true)
            } catch (e: CancellationException) {
                caughtCancellation = true
            }
        }

        // Wait until second search is waiting inside the rate limiter delay
        assertTrue("Second search must enter limiter delay", limiterDelayEntered.await(3, TimeUnit.SECONDS))
        // Reset flag to verify no new HTTP request is dispatched
        requestDispatched.set(false)

        // Cancel while waiting for limiter
        job.cancel()
        job.join()

        assertTrue("CancellationException must propagate", caughtCancellation)
        assertFalse("Second HTTP request must not be dispatched", requestDispatched.get())
        assertFalse("Second search must not complete normally", secondRequestAttempted.get())
    }

    @Test
    fun testLruCacheEvictionBeyondMaxSize() = runBlocking {
        val requestCount = java.util.concurrent.atomic.AtomicInteger(0)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""[{"place_id":1,"lat":"15.3","lon":"75.1","display_name":"Place"}]""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(
            httpClient = client,
            minRequestIntervalMs = 0L
        )

        // Fill cache up to DEFAULT_CACHE_MAX_SIZE (50)
        for (i in 1..50) {
            repo.search("UniqueQuery$i")
        }
        assertEquals(50, requestCount.get())
        assertEquals(50, repo.cacheSize())

        // Insert 51st entry, which should evict the oldest (UniqueQuery1)
        repo.search("UniqueQuery51")
        assertEquals(51, requestCount.get())
        assertEquals(50, repo.cacheSize())

        // Searching UniqueQuery51 again should hit cache (count remains 51)
        repo.search("UniqueQuery51")
        assertEquals(51, requestCount.get())

        // Searching evicted UniqueQuery1 should be a cache miss and make a new HTTP request
        repo.search("UniqueQuery1")
        assertEquals(52, requestCount.get())
    }

    // --- Issue #10B: HTTP 429 Too Many Requests Classification Tests ---

    @Test
    fun testHttp429ProducesRateLimitedNotNetworkError() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("Too Many Requests".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client, minRequestIntervalMs = 0L)
        val result = repo.search("Some Unknown City")

        assertTrue("Result must be RateLimited", result is LocationSearchResult.RateLimited)
        assertFalse("Result must NOT be NetworkError", result is LocationSearchResult.NetworkError)
        val rateLimited = result as LocationSearchResult.RateLimited
        assertEquals("Search service is temporarily busy. Please wait a moment and try again.", rateLimited.message)
    }

    @Test
    fun testHttp429DoesNotTriggerFallbackHttpRequests() = runBlocking {
        val requestCount = java.util.concurrent.atomic.AtomicInteger(0)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("Rate Limited".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(
            httpClient = client,
            defaultCountryCode = "in",
            minRequestIntervalMs = 0L
        )

        // "bvbcet" is a candidate query with alias expansions and global fallback if empty
        val result = repo.search("bvbcet")

        assertTrue("Result must be RateLimited", result is LocationSearchResult.RateLimited)
        val rateLimited = result as LocationSearchResult.RateLimited
        assertEquals("Search service is temporarily busy. Please wait a moment and try again.", rateLimited.message)
        assertEquals("Exactly one HTTP request must be dispatched on 429; no fallbacks", 1, requestCount.get())
    }

    @Test
    fun testRateLimitedIsNotCached() = runBlocking {
        val requestCount = java.util.concurrent.atomic.AtomicInteger(0)
        var return429 = true

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                if (return429) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("Too Many Requests".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""[{"place_id":1,"lat":"15.36","lon":"75.12","display_name":"Hubballi"}]""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val repo = DefaultLocationSearchRepository(
            httpClient = client,
            minRequestIntervalMs = 0L
        )

        // First search returns 429 RateLimited
        val result1 = repo.search("Hubballi")
        assertTrue("First search returns RateLimited", result1 is LocationSearchResult.RateLimited)
        assertEquals(1, requestCount.get())

        // Clear rate-limit condition
        return429 = false

        // Second search with same query should NOT return cached RateLimited; must reach HTTP layer
        val result2 = repo.search("Hubballi")
        assertTrue("Second search succeeds after rate-limit clears", result2 is LocationSearchResult.Success)
        assertEquals("Second search dispatched a second HTTP request (retry allowed)", 2, requestCount.get())
    }

    @Test
    fun testHttp500ProducesNetworkErrorNotRateLimited() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("Internal Server Error".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client, minRequestIntervalMs = 0L)
        val result = repo.search("Hubballi")

        assertTrue("HTTP 500 must produce NetworkError", result is LocationSearchResult.NetworkError)
        assertFalse("HTTP 500 must NOT produce RateLimited", result is LocationSearchResult.RateLimited)
        val networkError = result as LocationSearchResult.NetworkError
        assertEquals("Unable to search locations. Check your internet connection.", networkError.message)
    }

    @Test
    fun testCancellationWithHttp429RethrowsCancellationException() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val callCancelledLatch = CountDownLatch(1)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val call = chain.call()
                requestStarted.countDown()
                while (!call.isCanceled()) {
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                if (call.isCanceled()) {
                    callCancelledLatch.countDown()
                    throw IOException("Canceled")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("Too Many Requests".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val repo = DefaultLocationSearchRepository(httpClient = client, minRequestIntervalMs = 0L)

        var caughtCancellation = false
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.search("Hubballi")
            } catch (e: CancellationException) {
                caughtCancellation = true
            }
        }

        assertTrue("Request must start", requestStarted.await(3, TimeUnit.SECONDS))
        job.cancel()
        assertTrue("OkHttp Call must receive cancel() invocation", callCancelledLatch.await(3, TimeUnit.SECONDS))
        job.join()

        assertTrue("CancellationException must propagate even when cancellation is triggered", caughtCancellation)
    }
}
