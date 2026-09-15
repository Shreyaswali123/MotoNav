package com.example.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchBenchmarkUnitTest {

    @Test
    fun testPhotonParserValidGeoJson() {
        val sampleJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": {
                    "coordinates": [75.1186, 15.3358],
                    "type": "Point"
                  },
                  "properties": {
                    "osm_id": 1001,
                    "osm_type": "N",
                    "osm_key": "amenity",
                    "osm_value": "place_of_worship",
                    "name": "Shri Siddharoodha Swami Math",
                    "city": "Hubballi",
                    "state": "Karnataka",
                    "country": "India",
                    "type": "place_of_worship"
                  }
                },
                {
                  "type": "Feature",
                  "geometry": {
                    "coordinates": [75.1326, 15.3614],
                    "type": "Point"
                  },
                  "properties": {
                    "osm_id": 1002,
                    "name": "KIMS Hospital",
                    "city": "Hubballi",
                    "state": "Karnataka",
                    "country": "India",
                    "type": "hospital"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = PhotonParser.parse(sampleJson)
        assertEquals(2, parsed.size)

        val first = parsed[0]
        assertEquals("Shri Siddharoodha Swami Math", first.name)
        assertEquals(15.3358, first.latitude, 0.0001)
        assertEquals(75.1186, first.longitude, 0.0001)
        assertEquals("Hubballi", first.city)
        assertEquals("Karnataka", first.state)
        assertEquals("India", first.country)
        assertEquals("place_of_worship", first.resultType)
        assertEquals("amenity", first.category)
        assertEquals("photon", first.provider)
        assertTrue(first.isValid())
    }

    @Test
    fun testPhotonParserInvalidCoordinatesSkipped() {
        val jsonWithInvalidCoords = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": {
                    "coordinates": [999.0, 999.0],
                    "type": "Point"
                  },
                  "properties": { "name": "Invalid Location" }
                },
                {
                  "type": "Feature",
                  "geometry": {
                    "coordinates": [75.1240, 15.3647],
                    "type": "Point"
                  },
                  "properties": { "name": "Valid Location" }
                }
              ]
            }
        """.trimIndent()

        val parsed = PhotonParser.parse(jsonWithInvalidCoords)
        assertEquals(1, parsed.size)
        assertEquals("Valid Location", parsed[0].name)
    }

    @Test
    fun testPhotonParserEmptyAndMalformed() {
        assertTrue(PhotonParser.parse("").isEmpty())
        assertTrue(PhotonParser.parse("{}").isEmpty())
        assertTrue(PhotonParser.parse("{\"features\":[]}").isEmpty())
        assertTrue(PhotonParser.parse("invalid-json").isEmpty())
    }

    @Test
    fun testBenchmarkRunnerTop1HitEvaluation() {
        val target = SearchBenchmarkDataset.TARGET_SIDDHAROODHA_MATH
        val candidates = listOf(
            BenchmarkCandidate("Shri Siddharoodha Swami Math", "Hubballi, Karnataka", 15.3358, 75.1186),
            BenchmarkCandidate("Other Place", "Hubballi", 15.3400, 75.1200)
        )

        val result = SearchBenchmarkRunner.evaluateCandidates(
            providerName = "photon",
            query = "siddharudha math",
            hasLocationBias = true,
            isSuccess = true,
            httpStatus = 200,
            latencyMs = 450,
            candidateLocations = candidates,
            expectedTarget = target,
            originLat = 15.3647,
            originLon = 75.1240
        )

        assertTrue(result.isSuccess)
        assertFalse(result.isEmpty)
        assertEquals(true, result.isExpectedTop1)
        assertEquals(true, result.isExpectedInTop3)
        assertEquals(1.0, result.reciprocalRank ?: 0.0, 0.001)
        assertNotNull(result.distanceToExpectedKm)
        assertTrue((result.distanceToExpectedKm ?: 100.0) < 1.0)
    }

    @Test
    fun testBenchmarkRunnerTop3HitEvaluation() {
        val target = SearchBenchmarkDataset.TARGET_SIDDHAROODHA_MATH
        val candidates = listOf(
            BenchmarkCandidate("Random Place 1", "Address 1", 15.3700, 75.1200),
            BenchmarkCandidate("Shri Siddharoodha Swami Math", "Hubballi, Karnataka", 15.3358, 75.1186)
        )

        val result = SearchBenchmarkRunner.evaluateCandidates(
            providerName = "photon",
            query = "siddharuda",
            hasLocationBias = true,
            isSuccess = true,
            httpStatus = 200,
            latencyMs = 500,
            candidateLocations = candidates,
            expectedTarget = target,
            originLat = 15.3647,
            originLon = 75.1240
        )

        assertFalse(result.isExpectedTop1 == true)
        assertTrue(result.isExpectedInTop3 == true)
        assertEquals(0.5, result.reciprocalRank ?: 0.0, 0.001)
    }

    @Test
    fun testBenchmarkRunnerEmptyResultEvaluation() {
        val target = SearchBenchmarkDataset.TARGET_SIDDHAROODHA_MATH
        val result = SearchBenchmarkRunner.evaluateCandidates(
            providerName = "nominatim",
            query = "siddharuda mata",
            hasLocationBias = true,
            isSuccess = true,
            httpStatus = 200,
            latencyMs = 300,
            candidateLocations = emptyList(),
            expectedTarget = target,
            originLat = 15.3647,
            originLon = 75.1240
        )

        assertTrue(result.isEmpty)
        assertEquals(0, result.candidateCount)
        assertEquals(false, result.isExpectedInTop3)
        assertEquals(0.0, result.reciprocalRank ?: 0.0, 0.001)
    }

    @Test
    fun testAggregateMetricsCalculation() {
        val results = listOf(
            ProviderResult(
                provider = "photon",
                query = "q1",
                hasLocationBias = true,
                isSuccess = true,
                httpStatus = 200,
                latencyMs = 1000,
                candidateCount = 3,
                candidateNames = listOf("A"),
                topResultName = "A",
                topResultAddress = "Addr",
                topResultLat = 15.3,
                topResultLon = 75.1,
                top3CandidateNames = listOf("A"),
                isExpectedInTop3 = true,
                isExpectedTop1 = true,
                distanceToExpectedKm = 0.5,
                reciprocalRank = 1.0,
                hasDuplicates = false,
                isParseFailure = false,
                isEmpty = false
            ),
            ProviderResult(
                provider = "photon",
                query = "q2",
                hasLocationBias = true,
                isSuccess = true,
                httpStatus = 200,
                latencyMs = 1400,
                candidateCount = 0,
                candidateNames = emptyList(),
                topResultName = null,
                topResultAddress = null,
                topResultLat = null,
                topResultLon = null,
                top3CandidateNames = emptyList(),
                isExpectedInTop3 = false,
                isExpectedTop1 = false,
                distanceToExpectedKm = null,
                reciprocalRank = 0.0,
                hasDuplicates = false,
                isParseFailure = false,
                isEmpty = true
            )
        )

        val metrics = SearchBenchmarkRunner.calculateAggregateMetrics("photon", results, 2)
        assertEquals(2, metrics.totalEvaluated)
        assertEquals(1, metrics.top1Hits)
        assertEquals(50.0, metrics.top1HitRate, 0.01)
        assertEquals(1, metrics.top3Hits)
        assertEquals(50.0, metrics.top3HitRate, 0.01)
        assertEquals(1200.0, metrics.averageLatencyMs, 0.01)
        assertEquals(1200.0, metrics.medianLatencyMs, 0.01)
        assertEquals(1, metrics.emptyResultCount)
        assertEquals(50.0, metrics.emptyResultRate, 0.01)
        assertEquals(0, metrics.errorCount)
    }

    @Test
    fun testDeterministicMockedBenchmarkRunAll13Queries() {
        val comparisons = mutableListOf<SingleQueryBenchmarkComparison>()

        for (testCase in SearchBenchmarkDataset.QUERIES) {
            // Mocked results simulating known behavior for unit testing
            val mockNomCandidates = when (testCase.id) {
                "Q05" -> listOf(BenchmarkCandidate("Chandramouleshwara Temple", "Edapally Highway, Kolnad", 13.0637, 74.8024))
                "Q06" -> listOf(BenchmarkCandidate("Unkal Lake BRT Station", "Pune Bangalore Road, Hubballi", 15.3823, 75.1119))
                "Q07" -> listOf(BenchmarkCandidate("KIMS Hospital (u/c)", "Doddanekundi, Bengaluru", 12.9796, 77.6939))
                "Q08" -> listOf(BenchmarkCandidate("gokul road", "Thakur Complex, Mumbai", 19.2114, 72.8624))
                "Q09" -> listOf(BenchmarkCandidate("Vidyanagar", "Nallakunta, Hyderabad", 17.4019, 78.5096))
                "Q13" -> listOf(BenchmarkCandidate("Ghanta Ghar", "Dehradun, Uttarakhand", 30.3243, 78.0419))
                else -> emptyList()
            }

            val mockPhotNoBiasCandidates = when (testCase.id) {
                "Q01", "Q12" -> listOf(BenchmarkCandidate("Shri Mata Vaishno Devi Katra", "Jammu", 32.9830, 74.9350))
                "Q02" -> listOf(BenchmarkCandidate("Jay Mata Ji General Stores", "Nepal", 27.5068, 83.4488))
                "Q03" -> listOf(BenchmarkCandidate("Shri Siddharoodha Swami Math", "Hubballi, Karnataka", 15.3358, 75.1186))
                "Q06" -> listOf(BenchmarkCandidate("Unkal Lake BRT Station", "Hubballi, Karnataka", 15.3823, 75.1119))
                "Q07" -> listOf(BenchmarkCandidate("KIMS Hospital", "Maidstone, England", 51.2861, 0.5564))
                "Q08" -> listOf(BenchmarkCandidate("Gokul Road", "Umlazi, South Africa", -29.9751, 30.9276))
                "Q09" -> listOf(BenchmarkCandidate("Vidyanagar", "Telangana", 17.4019, 78.5096))
                "Q10" -> listOf(BenchmarkCandidate("Siddharuda complex", "Tumakuru, Karnataka", 13.3250, 77.1188))
                "Q13" -> listOf(BenchmarkCandidate("Jubilee Clock Tower", "Brighton, England", 50.8237, -0.1436))
                else -> emptyList()
            }

            val mockPhotBiasedCandidates = when (testCase.id) {
                "Q01", "Q12" -> listOf(
                    BenchmarkCandidate("Shri Mata Vaishno Devi Katra", "Jammu", 32.9830, 74.9350),
                    BenchmarkCandidate("Shri Bhagvati Mata Mandir", "Goa", 15.5670, 74.0067)
                )
                "Q02" -> listOf(BenchmarkCandidate("Jay Mata Ji General Stores", "Nepal", 27.5068, 83.4488))
                "Q03" -> listOf(BenchmarkCandidate("Shri Siddharoodha Swami Math", "Hubballi, Karnataka", 15.3358, 75.1186))
                "Q05" -> listOf(BenchmarkCandidate("Chandramouleshwara Temple", "Lingappayyakadu, Karnataka", 13.0637, 74.8024))
                "Q06" -> listOf(BenchmarkCandidate("Unkal Lake BRT Station", "Hubballi, Karnataka", 15.3823, 75.1119))
                "Q07" -> listOf(BenchmarkCandidate("KIMS Hospital", "Hubballi, Karnataka", 15.3614, 75.1326))
                "Q08" -> listOf(BenchmarkCandidate("Gokul Road / Airport Road", "Hubballi, Karnataka", 15.3541, 75.1308))
                "Q09" -> listOf(BenchmarkCandidate("Vidyanagar", "Hubballi, Karnataka", 15.3638, 75.1276))
                "Q10" -> listOf(BenchmarkCandidate("Siddharuda complex", "Tumakuru, Karnataka", 13.3250, 77.1188))
                "Q13" -> listOf(BenchmarkCandidate("Clock Tower", "Davanagere, Karnataka", 14.4696, 75.9199))
                else -> emptyList()
            }

            val nomRes = SearchBenchmarkRunner.evaluateCandidates(
                providerName = "nominatim",
                query = testCase.query,
                hasLocationBias = false,
                isSuccess = true,
                httpStatus = 200,
                latencyMs = 500,
                candidateLocations = mockNomCandidates,
                expectedTarget = testCase.expectedTarget,
                originLat = testCase.originLat,
                originLon = testCase.originLon
            )

            val photNoBiasRes = SearchBenchmarkRunner.evaluateCandidates(
                providerName = "photon",
                query = testCase.query,
                hasLocationBias = false,
                isSuccess = true,
                httpStatus = 200,
                latencyMs = 1400,
                candidateLocations = mockPhotNoBiasCandidates,
                expectedTarget = testCase.expectedTarget,
                originLat = testCase.originLat,
                originLon = testCase.originLon
            )

            val photBiasedRes = SearchBenchmarkRunner.evaluateCandidates(
                providerName = "photon",
                query = testCase.query,
                hasLocationBias = true,
                isSuccess = true,
                httpStatus = 200,
                latencyMs = 1400,
                candidateLocations = mockPhotBiasedCandidates,
                expectedTarget = testCase.expectedTarget,
                originLat = testCase.originLat,
                originLon = testCase.originLon
            )

            val comp = SearchBenchmarkRunner.compareQuery(testCase, nomRes, photNoBiasRes, photBiasedRes)
            comparisons.add(comp)

            val reportEntry = SearchBenchmarkRunner.formatReportEntry(comp)
            assertTrue(reportEntry.contains("QUERY:"))
            assertTrue(reportEntry.contains("NOMINATIM:"))
            assertTrue(reportEntry.contains("PHOTON:"))
            assertTrue(reportEntry.contains("WINNER:"))
        }

        assertEquals(13, comparisons.size)

        val nomResults = comparisons.map { it.nominatim }
        val photBiasedResults = comparisons.map { it.photonBiased }
        val expectedCount = SearchBenchmarkDataset.QUERIES.count { it.expectedTarget != null }

        val nomMetrics = SearchBenchmarkRunner.calculateAggregateMetrics("nominatim", nomResults, expectedCount)
        val photMetrics = SearchBenchmarkRunner.calculateAggregateMetrics("photon", photBiasedResults, expectedCount)

        assertNotNull(nomMetrics)
        assertNotNull(photMetrics)
    }
}
