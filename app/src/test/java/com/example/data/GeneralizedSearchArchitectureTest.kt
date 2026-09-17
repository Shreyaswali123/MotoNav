package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive tests for the Generalized Search Quality Architecture:
 * - Normalization & Variant Expansion (transliteration, compound words, suffixes, abbreviations, bounding)
 * - Candidate Scoring & Ranking (discriminative tokens, geographic proximity, variant boost, strong result threshold)
 * - Search Orchestration (local store first, strong photon suppression, photon variant retry, nominatim fallback, deduplication)
 * - Regression matrix for all 18 canonical regional queries
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GeneralizedSearchArchitectureTest {

    // =========================================================================
    // A. NORMALIZATION & VARIANT EXPANSION
    // =========================================================================

    @Test
    fun testNormalizationCleansPunctuationAndWhitespace() {
        val result = PlaceQueryNormalizer.normalize("   Shri.   Siddharuda,   Mata!!  ")
        assertEquals("Shri Siddharuda Mata", result)

        val diacritics = PlaceQueryNormalizer.normalize("KLE Téchnological")
        assertEquals("KLE Technological", diacritics)

        val camel = PlaceQueryNormalizer.normalize("BudanGudda")
        assertEquals("Budan Gudda", camel)
    }

    @Test
    fun testCompoundVariantsSplittingAndJoining() {
        // "budanagudda" should produce "budan gudda"
        val variantsFromCompound = PlaceQueryNormalizer.generateVariants("budanagudda")
        assertTrue(
            "Variants of budanagudda must include 'budan gudda'. Actual: $variantsFromCompound",
            variantsFromCompound.any { it.equals("budan gudda", ignoreCase = true) }
        )

        // "vidyanagar" should produce "vidya nagar"
        val variantsVidyanagar = PlaceQueryNormalizer.generateVariants("vidyanagar")
        assertTrue(
            "Variants of vidyanagar must include 'vidya nagar'. Actual: $variantsVidyanagar",
            variantsVidyanagar.any { it.equals("vidya nagar", ignoreCase = true) }
        )

        // "budan gudda" should produce "budanagudda"
        val variantsSplit = PlaceQueryNormalizer.generateVariants("budan gudda")
        assertTrue(
            "Variants of 'budan gudda' must include 'budanagudda'. Actual: $variantsSplit",
            variantsSplit.any { it.equals("budanagudda", ignoreCase = true) }
        )
    }

    @Test
    fun testTransliterationVariants() {
        // "siddharooda math" -> "siddharuda math" / "siddharuda matha"
        val variants = PlaceQueryNormalizer.generateVariants("siddharooda math")
        assertTrue(
            "Variants of 'siddharooda math' must include 'siddharuda'. Actual: $variants",
            variants.any { it.contains("siddharuda", ignoreCase = true) }
        )
    }

    @Test
    fun testAbbreviationVariants() {
        // "kims hosp" -> "kims hospital"
        val variants = PlaceQueryNormalizer.generateVariants("kims hosp")
        assertTrue(
            "Variants of 'kims hosp' must include 'kims hospital'. Actual: $variants",
            variants.any { it.equals("kims hospital", ignoreCase = true) }
        )
    }

    @Test
    fun testVariantGenerationCountIsBounded() {
        val variants = PlaceQueryNormalizer.generateVariants("shri siddharoodagudda hosp rd")
        assertTrue("Variants must be bounded to at most 4. Actual: ${variants.size}", variants.size <= 4)
        assertFalse("Variants must not be empty", variants.isEmpty())
    }

    // =========================================================================
    // B. SCORING & RANKING
    // =========================================================================

    @Test
    fun testDiscriminativeTokenMatchBeatsGenericTokenMatch() {
        val query = "shri siddharuda mata"
        // Candidate A matches the discriminative token "siddharuda"
        val candA = SearchLocation(
            id = "a",
            name = "Siddharuda Temple Hubballi",
            address = "Karnataka",
            latitude = 15.34,
            longitude = 75.14
        )
        // Candidate B matches generic tokens "shri" and "mata"
        val candB = SearchLocation(
            id = "b",
            name = "Shri Mata Bhavan",
            address = "Karnataka",
            latitude = 15.34,
            longitude = 75.14
        )

        val scoreA = PlaceResultRanker.calculateScore(candA, query, 15.34, 75.14)
        val scoreB = PlaceResultRanker.calculateScore(candB, query, 15.34, 75.14)

        assertTrue("Candidate matching discriminative token must score higher ($scoreA vs $scoreB)", scoreA > scoreB)
    }

    @Test
    fun testNearbyCandidateBeatsDistantCandidateWithSameName() {
        val userLat = 15.36
        val userLon = 75.12

        val nearby = SearchLocation(
            id = "near",
            name = "Gokul Road",
            address = "Hubballi, Karnataka",
            latitude = 15.355,
            longitude = 75.099
        )
        val distant = SearchLocation(
            id = "far",
            name = "Gokul Road",
            address = "Faraway City, 800km away",
            latitude = 22.0,
            longitude = 80.0
        )

        val scoreNear = PlaceResultRanker.calculateScore(nearby, "gokul road", userLat, userLon)
        val scoreFar = PlaceResultRanker.calculateScore(distant, "gokul road", userLat, userLon)

        assertTrue("Nearby candidate must score significantly higher than distant match ($scoreNear vs $scoreFar)", scoreNear > scoreFar + 40.0)
    }

    @Test
    fun testVariantMatchBoostsCandidate() {
        // Query is single compound "budanagudda", candidate is "Budan Gudda"
        val cand = SearchLocation(
            id = "bg",
            name = "Budan Gudda",
            address = "Hubballi Rural",
            latitude = 15.32,
            longitude = 75.10
        )

        val score = PlaceResultRanker.calculateScore(cand, "budanagudda", 15.36, 75.12)
        assertTrue("Variant match should produce strong score ($score)", score >= PlaceResultRanker.DEFAULT_STRONG_RESULT_THRESHOLD)
    }

    @Test
    fun testStrongResultThresholdSeparatesGoodVsBadCandidates() {
        val good = SearchLocation(
            id = "good",
            name = "KIMS Hospital Hubballi",
            address = "Vidyanagar, Hubballi",
            latitude = 15.368,
            longitude = 75.123
        )
        val badDistant = SearchLocation(
            id = "bad",
            name = "Alexanderplatz",
            address = "Berlin, Germany",
            latitude = 52.52,
            longitude = 13.40
        )

        assertTrue(
            "Good matching candidate should be strong",
            PlaceResultRanker.isStrongResult(good, "kims hospital", 15.36, 75.12)
        )
        assertFalse(
            "Distant irrelevant candidate should not be strong",
            PlaceResultRanker.isStrongResult(badDistant, "kims hospital", 15.36, 75.12)
        )
    }

    @Test
    fun testNoLocationFallbackDoesNotDiscardValidPlaces() {
        val loc = SearchLocation(
            id = "loc1",
            name = "KLE Technological University",
            address = "Vidyanagar, Hubballi",
            latitude = 15.369,
            longitude = 75.123
        )

        // Without user coordinates (lat=null, lon=null)
        val score = PlaceResultRanker.calculateScore(loc, "kle technological university", null, null)
        assertTrue(
            "Valid place should still be considered strong without user coordinates ($score)",
            score >= PlaceResultRanker.DEFAULT_STRONG_RESULT_THRESHOLD
        )
    }

    // =========================================================================
    // C. ORCHESTRATION (LOCAL + REMOTE + FALLBACK)
    // =========================================================================

    @Test
    fun testLocalStoreRecentMatchesImmediately() {
        val repo = DefaultLocationSearchRepository()
        val recent = SearchLocation(
            id = "recent_1",
            name = "Custom Rider Base",
            address = "Hubballi Outskirts",
            latitude = 15.31,
            longitude = 75.11
        )
        repo.recordRecentLocation(recent)

        val localMatches = repo.searchLocal("rider base")
        assertFalse("Local search must find recorded recent location", localMatches.isEmpty())
        assertEquals("Custom Rider Base", localMatches[0].name)
    }

    @Test
    fun testStrongPhotonMatchSuppressesNominatim() = runBlocking {
        val nominatimCallCount = AtomicInteger(0)
        val mockNominatimClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                nominatimCallCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("[]".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val mockPhoton = object : PlaceSearchProvider {
            override val providerName: String = "MockPhoton"
            override suspend fun search(query: String, lat: Double?, lon: Double?, limit: Int): List<SearchLocation> {
                return listOf(
                    SearchLocation(
                        id = "photon_1",
                        name = "KLE Technological University",
                        address = "Vidyanagar, Pune-Bangalore Rd, Hubballi",
                        latitude = 15.3690,
                        longitude = 75.1236,
                        importance = 0.8
                    )
                )
            }
        }

        val repo = DefaultLocationSearchRepository(
            httpClient = mockNominatimClient,
            photonProvider = mockPhoton
        )

        val result = repo.search("kle tech", 15.36, 75.12)
        assertTrue("Result should be Success", result is LocationSearchResult.Success)
        assertEquals("Nominatim must NOT be called when Photon returns a strong match", 0, nominatimCallCount.get())
    }

    @Test
    fun testWeakPhotonTriggersQueryVariantPhotonSearch() = runBlocking {
        val photonQueries = mutableListOf<String>()

        val mockPhoton = object : PlaceSearchProvider {
            override val providerName: String = "MockPhoton"
            override suspend fun search(query: String, lat: Double?, lon: Double?, limit: Int): List<SearchLocation> {
                photonQueries.add(query)
                // Only return match on variant query, not the primary raw compound query
                if (query == "budan gudda") {
                    return listOf(
                        SearchLocation(
                            id = "bg_hill",
                            name = "Budan Gudda",
                            address = "Hill in Hubballi Rural",
                            latitude = 15.32,
                            longitude = 75.10,
                            importance = 0.6
                        )
                    )
                }
                return emptyList()
            }
        }

        val repo = DefaultLocationSearchRepository(
            httpClient = OkHttpClient(),
            photonProvider = mockPhoton
        )

        val result = repo.search("budanagudda", 15.36, 75.12)
        assertTrue("Result should succeed via variant", result is LocationSearchResult.Success)
        assertTrue("Photon should have been called with variant query. Actual: $photonQueries", photonQueries.size > 1)
        val success = result as LocationSearchResult.Success
        assertEquals("Budan Gudda", success.locations[0].name)
    }

    @Test
    fun testPersistentlyWeakPhotonTriggersNominatimFallback() = runBlocking {
        val nominatimCalled = AtomicInteger(0)
        val nominatimJson = """
            [
              {
                "place_id": 999,
                "lat": "15.3200",
                "lon": "75.1000",
                "name": "Budan Gudda Hill",
                "display_name": "Budan Gudda Hill, Hubballi Rural, Karnataka, India"
              }
            ]
        """.trimIndent()

        val mockNominatimClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                nominatimCalled.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(nominatimJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        // Photon returns completely empty/weak
        val emptyPhoton = object : PlaceSearchProvider {
            override val providerName: String = "MockPhoton"
            override suspend fun search(query: String, lat: Double?, lon: Double?, limit: Int): List<SearchLocation> {
                return emptyList()
            }
        }

        val repo = DefaultLocationSearchRepository(
            httpClient = mockNominatimClient,
            photonProvider = emptyPhoton
        )

        val result = repo.search("budanagudda", 15.36, 75.12)
        assertTrue("Result should succeed via Nominatim fallback", result is LocationSearchResult.Success)
        assertTrue("Nominatim must be invoked when Photon is empty", nominatimCalled.get() > 0)
        val success = result as LocationSearchResult.Success
        assertEquals("Budan Gudda Hill", success.locations[0].name)
    }

    @Test
    fun testDeduplicationMergesNearDuplicates() {
        val repo = DefaultLocationSearchRepository()
        val loc1 = SearchLocation(
            id = "p1",
            name = "KLE Technological University",
            address = "Vidyanagar, Hubballi",
            latitude = 15.36900,
            longitude = 75.12360
        )
        val loc2 = SearchLocation(
            id = "p2",
            name = "KLE Technological University",
            address = "Vidyanagar Main Rd, Hubballi, Karnataka",
            latitude = 15.36901, // ~1 meter apart
            longitude = 75.12361
        )

        val deduplicated = repo.deduplicateLocations(listOf(loc1), listOf(loc2))
        assertEquals("Near identical places must be merged into 1 entry", 1, deduplicated.size)
    }

    @Test
    fun testDeduplicationDoesNotMergeGenuinelyDifferentPlaces() {
        val repo = DefaultLocationSearchRepository()
        val loc1 = SearchLocation(
            id = "p1",
            name = "KLE Hospital Pharmacy",
            address = "Vidyanagar",
            latitude = 15.3690,
            longitude = 75.1236
        )
        val loc2 = SearchLocation(
            id = "p2",
            name = "Vidyanagar Post Office",
            address = "Vidyanagar",
            latitude = 15.3720, // ~350 meters away with different name
            longitude = 75.1260
        )

        val deduplicated = repo.deduplicateLocations(listOf(loc1), listOf(loc2))
        assertEquals("Genuinely different places must NOT be merged", 2, deduplicated.size)
    }

    // =========================================================================
    // D. REGRESSION TEST MATRIX (18 CANONICAL QUERIES)
    // =========================================================================

    private val testCorpus = listOf(
        SearchLocation("1", "Budan Gudda", "Hill, Hubballi Rural, Karnataka", 15.3200, 75.1000, placeType = "hill"),
        SearchLocation("2", "Shri Siddharuda Matha", "Station Rd, Hubballi, Karnataka", 15.3400, 75.1450, placeType = "monument"),
        SearchLocation("3", "KLE Technological University", "Vidyanagar, Pune-Bangalore Rd, Hubballi", 15.3690, 75.1236, placeType = "university"),
        SearchLocation("4", "KIMS Hospital", "Vidyanagar, Hubballi, Karnataka", 15.3680, 75.1220, placeType = "hospital"),
        SearchLocation("5", "TolanKere", "Vivekanand Nagar, Hubballi, Karnataka", 15.3582, 75.1032, placeType = "lake"),
        SearchLocation("6", "Unkal Lake", "Gokul Rd, Hubballi, Karnataka", 15.3785, 75.1168, placeType = "lake"),
        SearchLocation("7", "Hubballi Railway Station", "Station Rd, Hubballi, Karnataka", 15.3486, 75.1481, placeType = "station"),
        SearchLocation("8", "Dharwad Old Bus Stand", "Line Bazaar, Dharwad, Karnataka", 15.4589, 75.0078, placeType = "bus_station"),
        SearchLocation("9", "IIT Dharwad", "Permanent Campus, Dharwad, Karnataka", 15.4889, 74.9339, placeType = "university"),
        SearchLocation("10", "Vidyanagar", "Vidyanagar Main Hub, Hubballi, Karnataka", 15.3647, 75.1240, placeType = "suburb")
    )

    private fun verifyQueryRanksTargetTop(query: String, expectedTargetName: String) {
        val ranked = PlaceResultRanker.rankResults(
            locations = testCorpus,
            query = query,
            userLatitude = 15.36,
            userLongitude = 75.12
        )
        assertFalse("Ranked results must not be empty for query '$query'", ranked.isEmpty())
        val top3Names = ranked.take(3).map { it.name }
        assertTrue(
            "Query '$query' should have '$expectedTargetName' in top 3. Actual top 3: $top3Names",
            top3Names.contains(expectedTargetName)
        )
        assertEquals(
            "Query '$query' should ideally rank '$expectedTargetName' as #1",
            expectedTargetName,
            ranked[0].name
        )
    }

    @Test
    fun testRegression1_budanagudda() = verifyQueryRanksTargetTop("budanagudda", "Budan Gudda")

    @Test
    fun testRegression2_budan_gudda() = verifyQueryRanksTargetTop("budan gudda", "Budan Gudda")

    @Test
    fun testRegression3_budangudda() = verifyQueryRanksTargetTop("budangudda", "Budan Gudda")

    @Test
    fun testRegression4_shri_siddharuda_mata() = verifyQueryRanksTargetTop("shri siddharuda mata", "Shri Siddharuda Matha")

    @Test
    fun testRegression5_siddharooda_math() = verifyQueryRanksTargetTop("siddharooda math", "Shri Siddharuda Matha")

    @Test
    fun testRegression6_siddharudha_matha_hubli() = verifyQueryRanksTargetTop("siddharudha matha hubli", "Shri Siddharuda Matha")

    @Test
    fun testRegression7_kle_tech() = verifyQueryRanksTargetTop("kle tech", "KLE Technological University")

    @Test
    fun testRegression8_kle_technological_university() = verifyQueryRanksTargetTop("kle technological university", "KLE Technological University")

    @Test
    fun testRegression9_kims_hospital() = verifyQueryRanksTargetTop("kims hospital", "KIMS Hospital")

    @Test
    fun testRegression10_kims_hubballi() = verifyQueryRanksTargetTop("kims hubballi", "KIMS Hospital")

    @Test
    fun testRegression11_tolankere() = verifyQueryRanksTargetTop("tolankere", "TolanKere")

    @Test
    fun testRegression12_tolan_kere() = verifyQueryRanksTargetTop("tolan kere", "TolanKere")

    @Test
    fun testRegression13_unkal_lake() = verifyQueryRanksTargetTop("unkal lake", "Unkal Lake")

    @Test
    fun testRegression14_hubballi_railway_station() = verifyQueryRanksTargetTop("hubballi railway station", "Hubballi Railway Station")

    @Test
    fun testRegression15_hubli_station() = verifyQueryRanksTargetTop("hubli station", "Hubballi Railway Station")

    @Test
    fun testRegression16_dharwad_bus_stand() = verifyQueryRanksTargetTop("dharwad bus stand", "Dharwad Old Bus Stand")

    @Test
    fun testRegression17_iit_dharwad() = verifyQueryRanksTargetTop("iit dharwad", "IIT Dharwad")

    @Test
    fun testRegression18_vidyanagar() = verifyQueryRanksTargetTop("vidyanagar", "Vidyanagar")
}
