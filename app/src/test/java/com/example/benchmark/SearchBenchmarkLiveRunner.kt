package com.example.benchmark

import com.example.data.DefaultLocationSearchRepository
import com.example.data.LocationSearchResult
import com.example.data.SearchLocation
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

object SearchBenchmarkLiveRunner {

    fun runLiveBenchmark(
        photonClient: PhotonSearchClient = PhotonSearchClient(),
        nominatimRepository: DefaultLocationSearchRepository = DefaultLocationSearchRepository.instance,
        queries: List<BenchmarkQueryCase> = SearchBenchmarkDataset.QUERIES
    ): Pair<List<SingleQueryBenchmarkComparison>, Pair<AggregateMetrics, AggregateMetrics>> = runBlocking {
        val comparisons = mutableListOf<SingleQueryBenchmarkComparison>()
        val nomResults = mutableListOf<ProviderResult>()
        val photBiasedResults = mutableListOf<ProviderResult>()
        val photNoBiasResults = mutableListOf<ProviderResult>()

        println("================================================================================")
        println("STARTING MOTONAV SEARCH BENCHMARK: NOMINATIM VS PHOTON")
        println("Queries: ${queries.size} | Geographic context: Hubballi (${SearchBenchmarkDataset.HUBBALLI_CENTER_LAT}, ${SearchBenchmarkDataset.HUBBALLI_CENTER_LON})")
        println("================================================================================")

        for ((index, testCase) in queries.withIndex()) {
            println("[${index + 1}/${queries.size}] Evaluating query: \"${testCase.query}\"...")

            // 1. Evaluate Nominatim (via existing DefaultLocationSearchRepository)
            val nomStartTime = System.nanoTime()
            val nomSearchResult = nominatimRepository.search(
                query = testCase.query,
                userLatitude = testCase.originLat,
                userLongitude = testCase.originLon
            )
            val nomLatencyMs = (System.nanoTime() - nomStartTime) / 1_000_000L

            val nomCandidates: List<BenchmarkCandidate> = when (nomSearchResult) {
                is LocationSearchResult.Success -> nomSearchResult.locations.map {
                    BenchmarkCandidate(it.name, it.address, it.latitude, it.longitude)
                }
                else -> emptyList()
            }

            val nomProviderResult = SearchBenchmarkRunner.evaluateCandidates(
                providerName = "nominatim",
                query = testCase.query,
                hasLocationBias = testCase.originLat != null,
                isSuccess = nomSearchResult !is LocationSearchResult.NetworkError,
                httpStatus = if (nomSearchResult is LocationSearchResult.RateLimited) 429 else 200,
                latencyMs = nomLatencyMs,
                candidateLocations = nomCandidates,
                expectedTarget = testCase.expectedTarget,
                originLat = testCase.originLat,
                originLon = testCase.originLon,
                isParseFailure = nomSearchResult is LocationSearchResult.MalformedResponse
            )
            nomResults.add(nomProviderResult)

            // Pacing between requests
            Thread.sleep(500)

            // 2. Evaluate Photon WITHOUT location context
            val photNoBiasResult = photonClient.search(
                query = testCase.query,
                latitude = null,
                longitude = null,
                limit = 5
            )

            val photNoBiasCandidates = when (photNoBiasResult) {
                is PhotonSearchResult.Success -> photNoBiasResult.locations.map {
                    BenchmarkCandidate(it.name, it.displayName, it.latitude, it.longitude)
                }
                else -> emptyList()
            }

            val photNoBiasProviderResult = SearchBenchmarkRunner.evaluateCandidates(
                providerName = "photon_nobias",
                query = testCase.query,
                hasLocationBias = false,
                isSuccess = photNoBiasResult !is PhotonSearchResult.Error,
                httpStatus = when (photNoBiasResult) {
                    is PhotonSearchResult.Success -> photNoBiasResult.httpStatus
                    is PhotonSearchResult.Empty -> photNoBiasResult.httpStatus
                    is PhotonSearchResult.Error -> photNoBiasResult.httpStatus
                },
                latencyMs = when (photNoBiasResult) {
                    is PhotonSearchResult.Success -> photNoBiasResult.latencyMs
                    is PhotonSearchResult.Empty -> photNoBiasResult.latencyMs
                    is PhotonSearchResult.Error -> photNoBiasResult.latencyMs
                },
                candidateLocations = photNoBiasCandidates,
                expectedTarget = testCase.expectedTarget,
                originLat = testCase.originLat,
                originLon = testCase.originLon
            )
            photNoBiasResults.add(photNoBiasProviderResult)

            Thread.sleep(500)

            // 3. Evaluate Photon WITH location context
            val photBiasedResult = photonClient.search(
                query = testCase.query,
                latitude = testCase.originLat,
                longitude = testCase.originLon,
                limit = 5
            )

            val photBiasedCandidates = when (photBiasedResult) {
                is PhotonSearchResult.Success -> photBiasedResult.locations.map {
                    BenchmarkCandidate(it.name, it.displayName, it.latitude, it.longitude)
                }
                else -> emptyList()
            }

            val photBiasedProviderResult = SearchBenchmarkRunner.evaluateCandidates(
                providerName = "photon_biased",
                query = testCase.query,
                hasLocationBias = true,
                isSuccess = photBiasedResult !is PhotonSearchResult.Error,
                httpStatus = when (photBiasedResult) {
                    is PhotonSearchResult.Success -> photBiasedResult.httpStatus
                    is PhotonSearchResult.Empty -> photBiasedResult.httpStatus
                    is PhotonSearchResult.Error -> photBiasedResult.httpStatus
                },
                latencyMs = when (photBiasedResult) {
                    is PhotonSearchResult.Success -> photBiasedResult.latencyMs
                    is PhotonSearchResult.Empty -> photBiasedResult.latencyMs
                    is PhotonSearchResult.Error -> photBiasedResult.latencyMs
                },
                candidateLocations = photBiasedCandidates,
                expectedTarget = testCase.expectedTarget,
                originLat = testCase.originLat,
                originLon = testCase.originLon
            )
            photBiasedResults.add(photBiasedProviderResult)

            // Compare
            val comparison = SearchBenchmarkRunner.compareQuery(
                testCase = testCase,
                nominatimResult = nomProviderResult,
                photonNoBiasResult = photNoBiasProviderResult,
                photonBiasedResult = photBiasedProviderResult
            )
            comparisons.add(comparison)

            // Pacing for next query
            Thread.sleep(800)
        }

        val expectedCount = queries.count { it.expectedTarget != null }
        val nomMetrics = SearchBenchmarkRunner.calculateAggregateMetrics("Nominatim", nomResults, expectedCount)
        val photMetrics = SearchBenchmarkRunner.calculateAggregateMetrics("Photon (Biased)", photBiasedResults, expectedCount)

        Pair(comparisons, Pair(nomMetrics, photMetrics))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val (comparisons, metricsPair) = runLiveBenchmark()
        val (nomMetrics, photMetrics) = metricsPair

        println()
        println("================================================================================")
        println("SEARCH-RESULT COMPARISON OUTPUT (QUERY-BY-QUERY)")
        println("================================================================================")
        println()

        for (comp in comparisons) {
            println(SearchBenchmarkRunner.formatReportEntry(comp))
            println("--------------------------------------------------------------------------------")
        }

        println()
        println("================================================================================")
        println("AGGREGATE METRICS SUMMARY")
        println("================================================================================")
        println("Total Queries Evaluated: ${comparisons.size}")
        println("Queries with Ground Truth: ${SearchBenchmarkDataset.QUERIES.count { it.expectedTarget != null }}")
        println()
        println(String.format(Locale.US, "%-30s | %-15s | %-15s", "Metric", "Nominatim", "Photon (Biased)"))
        println("--------------------------------------------------------------------------------")
        println(String.format(Locale.US, "%-30s | %-15s | %-15s", "Top-1 Hit Rate", "${String.format(Locale.US, "%.1f", nomMetrics.top1HitRate)}% (${nomMetrics.top1Hits})", "${String.format(Locale.US, "%.1f", photMetrics.top1HitRate)}% (${photMetrics.top1Hits})"))
        println(String.format(Locale.US, "%-30s | %-15s | %-15s", "Top-3 Hit Rate", "${String.format(Locale.US, "%.1f", nomMetrics.top3HitRate)}% (${nomMetrics.top3Hits})", "${String.format(Locale.US, "%.1f", photMetrics.top3HitRate)}% (${photMetrics.top3Hits})"))
        println(String.format(Locale.US, "%-30s | %-15s | %-15s", "Average Latency", "${String.format(Locale.US, "%.0f", nomMetrics.averageLatencyMs)} ms", "${String.format(Locale.US, "%.0f", photMetrics.averageLatencyMs)} ms"))
        println(String.format(Locale.US, "%-30s | %-15s | %-15s", "Median Latency", "${String.format(Locale.US, "%.0f", nomMetrics.medianLatencyMs)} ms", "${String.format(Locale.US, "%.0f", photMetrics.medianLatencyMs)} ms"))
        println(String.format(Locale.US, "%-30s | %-15s | %-15s", "Empty Result Rate", "${String.format(Locale.US, "%.1f", nomMetrics.emptyResultRate)}% (${nomMetrics.emptyResultCount})", "${String.format(Locale.US, "%.1f", photMetrics.emptyResultRate)}% (${photMetrics.emptyResultCount})"))
        println(String.format(Locale.US, "%-30s | %-15s | %-15s", "Parse/Error Rate", "${String.format(Locale.US, "%.1f", nomMetrics.errorRate)}% (${nomMetrics.errorCount})", "${String.format(Locale.US, "%.1f", photMetrics.errorRate)}% (${photMetrics.errorCount})"))
        println("================================================================================")
    }
}
