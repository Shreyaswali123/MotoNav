package com.example.benchmark

import java.util.Locale

object SearchBenchmarkRunner {

    fun evaluateCandidates(
        providerName: String,
        query: String,
        hasLocationBias: Boolean,
        isSuccess: Boolean,
        httpStatus: Int,
        latencyMs: Long,
        candidateLocations: List<BenchmarkCandidate>,
        expectedTarget: ExpectedTarget?,
        originLat: Double?,
        originLon: Double?,
        isParseFailure: Boolean = false
    ): ProviderResult {
        if (!isSuccess || isParseFailure) {
            return ProviderResult(
                provider = providerName,
                query = query,
                hasLocationBias = hasLocationBias,
                isSuccess = false,
                httpStatus = httpStatus,
                latencyMs = latencyMs,
                candidateCount = 0,
                candidateNames = emptyList(),
                topResultName = null,
                topResultAddress = null,
                topResultLat = null,
                topResultLon = null,
                top3CandidateNames = emptyList(),
                isExpectedInTop3 = if (expectedTarget != null) false else null,
                isExpectedTop1 = if (expectedTarget != null) false else null,
                distanceToExpectedKm = null,
                reciprocalRank = if (expectedTarget != null) 0.0 else null,
                hasDuplicates = false,
                isParseFailure = isParseFailure,
                isEmpty = false
            )
        }

        if (candidateLocations.isEmpty()) {
            return ProviderResult(
                provider = providerName,
                query = query,
                hasLocationBias = hasLocationBias,
                isSuccess = true,
                httpStatus = httpStatus,
                latencyMs = latencyMs,
                candidateCount = 0,
                candidateNames = emptyList(),
                topResultName = null,
                topResultAddress = null,
                topResultLat = null,
                topResultLon = null,
                top3CandidateNames = emptyList(),
                isExpectedInTop3 = if (expectedTarget != null) false else null,
                isExpectedTop1 = if (expectedTarget != null) false else null,
                distanceToExpectedKm = null,
                reciprocalRank = if (expectedTarget != null) 0.0 else null,
                hasDuplicates = false,
                isParseFailure = false,
                isEmpty = true
            )
        }

        val candidateNames = candidateLocations.map { it.name }
        val topResult = candidateLocations.first()
        val top3Names = candidateLocations.take(3).map { it.name }

        // Check duplicates
        val uniqueKeys = candidateLocations.map { "${it.name}|${it.latitude}|${it.longitude}" }.toSet()
        val hasDuplicates = uniqueKeys.size < candidateLocations.size

        // Calculate expected target matches and reciprocal rank
        var isTop1 = false
        var isTop3 = false
        var bestDistance: Double? = null
        var reciprocalRank = 0.0

        if (expectedTarget != null) {
            for (idx in candidateLocations.indices) {
                val c = candidateLocations[idx]
                val matches = expectedTarget.matches(c.name, c.address, c.latitude, c.longitude)
                val dist = expectedTarget.distanceTo(c.latitude, c.longitude)
                if (bestDistance == null || dist < bestDistance) {
                    bestDistance = dist
                }

                if (matches) {
                    if (idx == 0) isTop1 = true
                    if (idx < 3) isTop3 = true
                    if (reciprocalRank == 0.0) {
                        reciprocalRank = 1.0 / (idx + 1)
                    }
                }
            }
        } else if (originLat != null && originLon != null) {
            bestDistance = ExpectedTarget.distanceBetweenKm(
                originLat, originLon, topResult.latitude, topResult.longitude
            )
        }

        return ProviderResult(
            provider = providerName,
            query = query,
            hasLocationBias = hasLocationBias,
            isSuccess = true,
            httpStatus = httpStatus,
            latencyMs = latencyMs,
            candidateCount = candidateLocations.size,
            candidateNames = candidateNames,
            topResultName = topResult.name,
            topResultAddress = topResult.address,
            topResultLat = topResult.latitude,
            topResultLon = topResult.longitude,
            top3CandidateNames = top3Names,
            isExpectedInTop3 = if (expectedTarget != null) isTop3 else null,
            isExpectedTop1 = if (expectedTarget != null) isTop1 else null,
            distanceToExpectedKm = bestDistance,
            reciprocalRank = if (expectedTarget != null) reciprocalRank else null,
            hasDuplicates = hasDuplicates,
            isParseFailure = false,
            isEmpty = false
        )
    }

    fun compareQuery(
        testCase: BenchmarkQueryCase,
        nominatimResult: ProviderResult,
        photonNoBiasResult: ProviderResult,
        photonBiasedResult: ProviderResult
    ): SingleQueryBenchmarkComparison {
        // Deterministic winner selection
        val winner: String
        val notes: String

        if (testCase.expectedTarget != null) {
            val nomHit = nominatimResult.isExpectedInTop3 == true
            val photHit = photonBiasedResult.isExpectedInTop3 == true
            val nomTop1 = nominatimResult.isExpectedTop1 == true
            val photTop1 = photonBiasedResult.isExpectedTop1 == true

            when {
                photTop1 && !nomTop1 -> {
                    winner = "Photon"
                    notes = "Photon returned expected location as Top 1; Nominatim missed Top 1"
                }
                nomTop1 && !photTop1 -> {
                    winner = "Nominatim"
                    notes = "Nominatim returned expected location as Top 1; Photon missed Top 1"
                }
                photHit && !nomHit -> {
                    winner = "Photon"
                    notes = "Photon returned expected location in Top 3; Nominatim had no top-3 hit"
                }
                nomHit && !photHit -> {
                    winner = "Nominatim"
                    notes = "Nominatim returned expected location in Top 3; Photon had no top-3 hit"
                }
                photTop1 && nomTop1 -> {
                    val nomDist = nominatimResult.distanceToExpectedKm ?: Double.MAX_VALUE
                    val photDist = photonBiasedResult.distanceToExpectedKm ?: Double.MAX_VALUE
                    if (Math.abs(nomDist - photDist) < 1.0) {
                        winner = "Tie"
                        notes = "Both returned accurate Top 1 candidate"
                    } else if (photDist < nomDist) {
                        winner = "Photon"
                        notes = "Both matched Top 1, but Photon was geographically closer"
                    } else {
                        winner = "Nominatim"
                        notes = "Both matched Top 1, but Nominatim was geographically closer"
                    }
                }
                photHit && nomHit -> {
                    val nomRr = nominatimResult.reciprocalRank ?: 0.0
                    val photRr = photonBiasedResult.reciprocalRank ?: 0.0
                    if (photRr > nomRr) {
                        winner = "Photon"
                        notes = "Photon ranked expected target higher (RR $photRr vs $nomRr)"
                    } else if (nomRr > photRr) {
                        winner = "Nominatim"
                        notes = "Nominatim ranked expected target higher (RR $nomRr vs $photRr)"
                    } else {
                        winner = "Tie"
                        notes = "Both found expected target at same rank"
                    }
                }
                else -> {
                    if (nominatimResult.isEmpty && photonBiasedResult.isEmpty) {
                        winner = "Tie"
                        notes = "Both returned 0 results"
                    } else if (!nominatimResult.isEmpty && photonBiasedResult.isEmpty) {
                        winner = "Inconclusive"
                        notes = "Neither matched expected target; Nominatim returned candidates"
                    } else if (nominatimResult.isEmpty && !photonBiasedResult.isEmpty) {
                        winner = "Photon"
                        notes = "Nominatim was completely empty; Photon returned relevant candidates"
                    } else {
                        winner = "Inconclusive"
                        notes = "Neither matched ground truth criteria"
                    }
                }
            }
        } else {
            // Unverified / exploratory queries (e.g. budan gudda, clock tower)
            if (testCase.originLat != null && testCase.originLon != null) {
                val nomDist = nominatimResult.distanceToExpectedKm
                val photDist = photonBiasedResult.distanceToExpectedKm

                if (nomDist != null && photDist != null) {
                    if (photDist < nomDist && photDist < 30.0) {
                        winner = "Photon"
                        notes = "Photon returned a local candidate closer to origin (${String.format(Locale.US, "%.1f", photDist)} km vs ${String.format(Locale.US, "%.1f", nomDist)} km)"
                    } else if (nomDist < photDist && nomDist < 30.0) {
                        winner = "Nominatim"
                        notes = "Nominatim returned a local candidate closer to origin (${String.format(Locale.US, "%.1f", nomDist)} km vs ${String.format(Locale.US, "%.1f", photDist)} km)"
                    } else {
                        winner = "Inconclusive"
                        notes = "No ground truth; candidates dispersed across regions"
                    }
                } else if (nominatimResult.isEmpty && !photonBiasedResult.isEmpty) {
                    winner = "Inconclusive"
                    notes = "Nominatim returned 0 results; Photon returned candidates without ground truth"
                } else {
                    winner = "Inconclusive"
                    notes = "Exploratory query without verified ground truth"
                }
            } else {
                winner = "Inconclusive"
                notes = "No location origin or ground truth established"
            }
        }

        return SingleQueryBenchmarkComparison(
            query = testCase.query,
            category = testCase.category,
            nominatim = nominatimResult,
            photonNoBias = photonNoBiasResult,
            photonBiased = photonBiasedResult,
            winner = winner,
            notes = notes
        )
    }

    fun calculateAggregateMetrics(
        providerName: String,
        results: List<ProviderResult>,
        expectedCasesCount: Int
    ): AggregateMetrics {
        if (results.isEmpty()) {
            return AggregateMetrics(providerName, 0, 0, 0.0, 0, 0.0, 0.0, 0.0, 0, 0.0, 0, 0.0)
        }

        val total = results.size
        val top1Hits = results.count { it.isExpectedTop1 == true }
        val top3Hits = results.count { it.isExpectedInTop3 == true }
        val denominator = if (expectedCasesCount > 0) expectedCasesCount else total

        val top1Rate = (top1Hits.toDouble() / denominator) * 100.0
        val top3Rate = (top3Hits.toDouble() / denominator) * 100.0

        val latencies = results.map { it.latencyMs }.sorted()
        val avgLatency = latencies.average()
        val medianLatency = if (latencies.size % 2 == 1) {
            latencies[latencies.size / 2].toDouble()
        } else {
            (latencies[latencies.size / 2 - 1] + latencies[latencies.size / 2]) / 2.0
        }

        val emptyCount = results.count { it.isEmpty }
        val emptyRate = (emptyCount.toDouble() / total) * 100.0

        val errorCount = results.count { !it.isSuccess || it.isParseFailure }
        val errorRate = (errorCount.toDouble() / total) * 100.0

        return AggregateMetrics(
            providerName = providerName,
            totalEvaluated = total,
            top1Hits = top1Hits,
            top1HitRate = top1Rate,
            top3Hits = top3Hits,
            top3HitRate = top3Rate,
            averageLatencyMs = avgLatency,
            medianLatencyMs = medianLatency,
            emptyResultCount = emptyCount,
            emptyResultRate = emptyRate,
            errorCount = errorCount,
            errorRate = errorRate
        )
    }

    fun formatReportEntry(comparison: SingleQueryBenchmarkComparison): String {
        val sb = StringBuilder()
        sb.appendLine("QUERY:")
        sb.appendLine("\"${comparison.query}\"")
        sb.appendLine()

        // NOMINATIM
        val nom = comparison.nominatim
        sb.appendLine("NOMINATIM:")
        sb.appendLine("Top 1: ${nom.top3CandidateNames.getOrElse(0) { "None" }}")
        sb.appendLine("Top 2: ${nom.top3CandidateNames.getOrElse(1) { "None" }}")
        sb.appendLine("Top 3: ${nom.top3CandidateNames.getOrElse(2) { "None" }}")
        sb.appendLine("Latency: ${nom.latencyMs} ms")
        val nomDistStr = nom.distanceToExpectedKm?.let { String.format(Locale.US, "%.2f km", it) } ?: "N/A"
        sb.appendLine("Distance: $nomDistStr")
        val nomExpectedStr = when (nom.isExpectedInTop3) {
            true -> "YES"
            false -> "NO"
            null -> "N/A (no ground truth)"
        }
        sb.appendLine("Expected result in top 3: $nomExpectedStr")
        sb.appendLine()

        // PHOTON (report biased as primary benchmark, note unbiased in parens)
        val phot = comparison.photonBiased
        sb.appendLine("PHOTON:")
        sb.appendLine("Top 1: ${phot.top3CandidateNames.getOrElse(0) { "None" }}")
        sb.appendLine("Top 2: ${phot.top3CandidateNames.getOrElse(1) { "None" }}")
        sb.appendLine("Top 3: ${phot.top3CandidateNames.getOrElse(2) { "None" }}")
        sb.appendLine("Latency: ${phot.latencyMs} ms (unbiased: ${comparison.photonNoBias.latencyMs} ms)")
        val photDistStr = phot.distanceToExpectedKm?.let { String.format(Locale.US, "%.2f km", it) } ?: "N/A"
        sb.appendLine("Distance: $photDistStr")
        val photExpectedStr = when (phot.isExpectedInTop3) {
            true -> "YES"
            false -> "NO"
            null -> "N/A (no ground truth)"
        }
        sb.appendLine("Expected result in top 3: $photExpectedStr")
        sb.appendLine()

        // WINNER
        sb.appendLine("WINNER:")
        sb.appendLine("${comparison.winner} (${comparison.notes})")
        return sb.toString()
    }
}

data class BenchmarkCandidate(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
)
