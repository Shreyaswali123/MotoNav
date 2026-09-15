package com.example.benchmark

data class ExpectedTarget(
    val nameKeywords: List<String>,
    val latitude: Double,
    val longitude: Double,
    val maxAcceptableDistanceKm: Double = 15.0
) {
    fun matches(name: String, address: String, lat: Double, lon: Double): Boolean {
        val text = "$name $address".lowercase()
        val keywordMatch = nameKeywords.any { kw -> text.contains(kw.lowercase()) }
        val dist = distanceBetweenKm(lat, lon, latitude, longitude)
        return keywordMatch && dist <= maxAcceptableDistanceKm
    }

    fun distanceTo(lat: Double, lon: Double): Double {
        return distanceBetweenKm(lat, lon, latitude, longitude)
    }

    companion object {
        fun distanceBetweenKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return 6371.0 * c
        }
    }
}

data class BenchmarkQueryCase(
    val id: String,
    val query: String,
    val category: String,
    val expectedTarget: ExpectedTarget? = null,
    val originLat: Double? = 15.3647,
    val originLon: Double? = 75.1240
)

data class ProviderResult(
    val provider: String,
    val query: String,
    val hasLocationBias: Boolean,
    val isSuccess: Boolean,
    val httpStatus: Int,
    val latencyMs: Long,
    val candidateCount: Int,
    val candidateNames: List<String>,
    val topResultName: String?,
    val topResultAddress: String?,
    val topResultLat: Double?,
    val topResultLon: Double?,
    val top3CandidateNames: List<String>,
    val isExpectedInTop3: Boolean?,
    val isExpectedTop1: Boolean?,
    val distanceToExpectedKm: Double?,
    val reciprocalRank: Double?,
    val hasDuplicates: Boolean,
    val isParseFailure: Boolean,
    val isEmpty: Boolean
)

data class SingleQueryBenchmarkComparison(
    val query: String,
    val category: String,
    val nominatim: ProviderResult,
    val photonNoBias: ProviderResult,
    val photonBiased: ProviderResult,
    val winner: String,
    val notes: String
)

data class AggregateMetrics(
    val providerName: String,
    val totalEvaluated: Int,
    val top1Hits: Int,
    val top1HitRate: Double,
    val top3Hits: Int,
    val top3HitRate: Double,
    val averageLatencyMs: Double,
    val medianLatencyMs: Double,
    val emptyResultCount: Int,
    val emptyResultRate: Double,
    val errorCount: Int,
    val errorRate: Double
)
