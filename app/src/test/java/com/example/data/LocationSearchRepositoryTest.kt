package com.example.data

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
}
