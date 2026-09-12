package com.example.route

import com.example.model.RoutePoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Result of the polyline simplification process.
 *
 * @property simplifiedPoints The reduced list of [RoutePoint] preserving shape and critical waypoints.
 * @property oldToNewIndexMap Mapping from the original shape index to the new index in [simplifiedPoints].
 */
data class SimplificationResult(
    val simplifiedPoints: List<RoutePoint>,
    val oldToNewIndexMap: Map<Int, Int>
) {
    /**
     * Helper to safely map an original shape index to the corresponding or nearest simplified index.
     */
    fun remapIndex(originalIndex: Int): Int {
        oldToNewIndexMap[originalIndex]?.let { return it }
        // If the exact index was simplified away, find the closest simplified point
        val keys = oldToNewIndexMap.keys.sorted()
        if (keys.isEmpty()) return 0
        val closestKey = keys.minByOrNull { kotlin.math.abs(it - originalIndex) } ?: keys.first()
        return oldToNewIndexMap[closestKey] ?: 0
    }
}

/**
 * Geographic polyline simplifier that preserves mandatory maneuver points,
 * first point, destination point, and retains smooth geometry with ~60m spacing.
 */
object DouglasPeuckerSimplifier {

    const val DEFAULT_SPACING_METERS = 60.0
    const val MAX_MOTONAV_POINTS = 750
    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calculates the great-circle (haversine) distance between two [RoutePoint]s in meters.
     */
    fun distanceMeters(p1: RoutePoint, p2: RoutePoint): Double {
        val lat1 = Math.toRadians(p1.latitude)
        val lon1 = Math.toRadians(p1.longitude)
        val lat2 = Math.toRadians(p2.latitude)
        val lon2 = Math.toRadians(p2.longitude)
        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        val a = sin(dLat / 2.0).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)
        val clampedA = a.coerceIn(0.0, 1.0)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(clampedA))
    }

    /**
     * Simplifies the given [points] while guaranteeing that:
     * 1. The first point (index 0) is preserved.
     * 2. The destination point (last index) is preserved.
     * 3. Every index in [mandatoryIndices] is preserved.
     * 4. Additional intermediate points are retained according to [maxSpacingMeters] (default 60m).
     *
     * @param points Complete list of decoded geographic points.
     * @param mandatoryIndices Indices that MUST NOT be removed (e.g. maneuver begin_shape_index values).
     * @param maxSpacingMeters Target maximum spacing between retained points in meters.
     * @return [SimplificationResult] containing the simplified points and index mapping.
     */
    fun simplify(
        points: List<RoutePoint>,
        mandatoryIndices: Set<Int> = emptySet(),
        maxSpacingMeters: Double = DEFAULT_SPACING_METERS
    ): SimplificationResult {
        if (points.isEmpty()) {
            return SimplificationResult(emptyList(), emptyMap())
        }
        if (points.size <= 2) {
            val mapping = points.indices.associateWith { it }
            return SimplificationResult(points, mapping)
        }

        // Build sorted set of mandatory indices
        val mandatorySet = mutableSetOf<Int>()
        mandatorySet.add(0)
        mandatorySet.add(points.size - 1)
        for (idx in mandatoryIndices) {
            if (idx in points.indices) {
                mandatorySet.add(idx)
            }
        }

        val keptIndices = mutableListOf<Int>()
        keptIndices.add(0)

        for (i in 1 until points.size) {
            val isMandatory = mandatorySet.contains(i)
            val lastKeptIndex = keptIndices.last()
            val dist = distanceMeters(points[lastKeptIndex], points[i])

            if (isMandatory || dist >= maxSpacingMeters) {
                keptIndices.add(i)
            }
        }

        // Ensure the very last point is definitely included
        val lastPointIdx = points.size - 1
        if (keptIndices.last() != lastPointIdx) {
            keptIndices.add(lastPointIdx)
        }

        val simplifiedPoints = keptIndices.map { points[it] }
        val oldToNewMap = keptIndices.mapIndexed { newIndex, oldIndex -> oldIndex to newIndex }.toMap()

        return SimplificationResult(
            simplifiedPoints = simplifiedPoints,
            oldToNewIndexMap = oldToNewMap
        )
    }

    /**
     * Adaptively simplifies [points] to guarantee that the output contains at most [maxPoints] (default 750).
     *
     * 1. Checks that the count of unique mandatory points (start, destination, and [mandatoryIndices])
     *    does not exceed [maxPoints]. If it does, throws [IllegalArgumentException].
     * 2. First attempts simplification using [initialSpacingMeters] (default 60m).
     * 3. If the first attempt results in <= [maxPoints], returns it unchanged.
     * 4. If the first attempt exceeds [maxPoints], adaptively increases spacing using a deterministic
     *    bounded search (exponential upper-bound discovery followed by binary search) to find the smallest
     *    spacing that yields <= [maxPoints] points, preserving maximum geometry detail.
     *
     * @param points Complete list of decoded geographic points.
     * @param mandatoryIndices Indices that MUST NOT be removed (e.g. maneuver begin_shape_index values).
     * @param initialSpacingMeters Starting spacing in meters (default 60m).
     * @param maxPoints Maximum allowable points in the simplified route (default 750).
     * @return [SimplificationResult] containing <= [maxPoints] points and index mapping.
     */
    fun simplifyAdaptive(
        points: List<RoutePoint>,
        mandatoryIndices: Set<Int> = emptySet(),
        initialSpacingMeters: Double = DEFAULT_SPACING_METERS,
        maxPoints: Int = MAX_MOTONAV_POINTS
    ): SimplificationResult {
        if (points.isEmpty()) {
            return SimplificationResult(emptyList(), emptyMap())
        }
        if (points.size <= 2) {
            val mapping = points.indices.associateWith { it }
            return SimplificationResult(points, mapping)
        }

        // Determine unique mandatory points
        val mandatorySet = mutableSetOf<Int>()
        mandatorySet.add(0)
        mandatorySet.add(points.size - 1)
        for (idx in mandatoryIndices) {
            if (idx in points.indices) {
                mandatorySet.add(idx)
            }
        }

        // If mandatory points alone exceed maxPoints, fail immediately
        if (mandatorySet.size > maxPoints) {
            throw IllegalArgumentException(
                "Mandatory route points (${mandatorySet.size}) exceed MAX_MOTONAV_POINTS ($maxPoints). Cannot simplify route without discarding mandatory maneuver points."
            )
        }

        // Attempt 1: Default / initial spacing (e.g. 60m)
        val firstAttempt = simplify(points, mandatorySet, initialSpacingMeters)
        if (firstAttempt.simplifiedPoints.size <= maxPoints) {
            return firstAttempt
        }

        // Adaptive refinement:
        // We know initialSpacingMeters produces > maxPoints.
        var lowSpacing = initialSpacingMeters
        var highSpacing = initialSpacingMeters * 2.0
        var bestResult: SimplificationResult? = null

        // Exponential expansion to find an upper bound (bounded to 25 iterations)
        var boundIterations = 0
        while (boundIterations < 25) {
            val candidate = simplify(points, mandatorySet, highSpacing)
            if (candidate.simplifiedPoints.size <= maxPoints) {
                bestResult = candidate
                break
            }
            lowSpacing = highSpacing
            highSpacing *= 2.0
            boundIterations++
        }

        if (bestResult == null) {
            // As spacing becomes very large, only mandatory points remain
            val fallback = simplify(points, mandatorySet, 100_000_000.0)
            if (fallback.simplifiedPoints.size <= maxPoints) {
                bestResult = fallback
            } else {
                throw IllegalStateException(
                    "Adaptive simplification failed to reduce route points to <= $maxPoints"
                )
            }
        }

        // Binary search between lowSpacing (> maxPoints) and highSpacing (<= maxPoints)
        // to find the smallest spacing (highest geometry detail) that meets <= maxPoints.
        var low = lowSpacing
        var high = highSpacing
        val maxBinarySearchIterations = 20

        for (i in 0 until maxBinarySearchIterations) {
            val mid = (low + high) / 2.0
            val candidate = simplify(points, mandatorySet, mid)
            if (candidate.simplifiedPoints.size <= maxPoints) {
                bestResult = candidate
                high = mid // Try smaller spacing
            } else {
                low = mid // Too many points, need larger spacing
            }
        }

        return bestResult ?: throw IllegalStateException(
            "Adaptive simplification failed to reduce route points to <= $maxPoints"
        )
    }
}
