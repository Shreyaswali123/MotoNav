package com.example.route

import com.example.model.RoutePoint
import com.example.network.ValhallaRouteResponse

/**
 * Detailed output from the high-level route conversion pipeline.
 *
 * @property serializedRoute The binary byte array and CRC32 ready for transmission.
 * @property decodedPoints Full-fidelity original decoded coordinates from Valhalla.
 * @property simplifiedPoints Decimated geographic points preserving maneuver positions.
 * @property maneuvers Converted MotoNav maneuver records.
 * @property totalDistanceMeters Total route distance in meters.
 * @property durationSeconds Estimated route travel duration in seconds.
 */
data class RouteConversionResult(
    val serializedRoute: SerializedMotoNavRoute,
    val decodedPoints: List<RoutePoint>,
    val simplifiedPoints: List<RoutePoint>,
    val maneuvers: List<MotoNavManeuverRecord>,
    val totalDistanceMeters: Int,
    val durationSeconds: Int
)

/**
 * High-level converter orchestrating:
 * Valhalla response
 * → decode Valhalla shape (1e6)
 * → identify maneuver shape indices
 * → simplify geometry while preserving maneuver points (~60m spacing)
 * → remap old Valhalla shape indices to new MotoNav point indices
 * → convert maneuvers (types 1, 2, 5)
 * → serialize MotoNav Route Format v1
 * → calculate CRC32
 */
object MotoNavRouteConverter {

    const val MAX_MOTONAV_POINTS = DouglasPeuckerSimplifier.MAX_MOTONAV_POINTS

    /**
     * Converts a [ValhallaRouteResponse] into a [RouteConversionResult].
     *
     * @param response Valhalla routing API response.
     * @param routeId Identifier to embed in the MotoNav v1 header.
     * @param maxSpacingMeters Target geometry point spacing in meters (default 60m).
     * @return [RouteConversionResult] with the serialized route and metadata.
     */
    fun convert(
        response: ValhallaRouteResponse,
        routeId: Long = 1L,
        maxSpacingMeters: Double = DouglasPeuckerSimplifier.DEFAULT_SPACING_METERS
    ): RouteConversionResult {
        val trip = response.trip ?: throw IllegalArgumentException("Valhalla response contains no trip")
        val leg = trip.legs?.firstOrNull() ?: throw IllegalArgumentException("Valhalla trip contains no legs")
        val shape = leg.shape ?: throw IllegalArgumentException("Valhalla route leg contains no shape data")

        // 1. Decode Valhalla shape (1e6)
        val decodedPoints = PolylineDecoder.decodePolyline6(shape)
        require(decodedPoints.isNotEmpty()) { "Decoded shape contains 0 points" }

        // 2. Identify maneuver shape indices and mandatory points
        val rawManeuvers = leg.maneuvers ?: emptyList()
        val convertedRawManeuvers = rawManeuvers.filter {
            ValhallaManeuverConverter.resolveMotoNavType(it) != null
        }
        val convertedManeuverShapeIndices = convertedRawManeuvers
            .map { it.beginShapeIndex }
            .filter { it in decodedPoints.indices }
            .toSet()

        // Mandatory points: route start (0), route destination (last), and every converted maneuver point
        val mandatoryIndices = buildSet {
            add(0)
            add(decodedPoints.size - 1)
            addAll(convertedManeuverShapeIndices)
        }

        // 3. Mandatory-point safety check
        if (mandatoryIndices.size > MAX_MOTONAV_POINTS) {
            throw IllegalArgumentException(
                "Mandatory route points (${mandatoryIndices.size}) exceed MAX_MOTONAV_POINTS ($MAX_MOTONAV_POINTS). Cannot simplify route without discarding mandatory maneuver points."
            )
        }

        // 4. Simplify geometry adaptively while strictly preserving all mandatory points
        val simplification = DouglasPeuckerSimplifier.simplifyAdaptive(
            points = decodedPoints,
            mandatoryIndices = mandatoryIndices,
            initialSpacingMeters = maxSpacingMeters,
            maxPoints = MAX_MOTONAV_POINTS
        )

        // 5. Remap old Valhalla shape indices to new simplified point indices and convert maneuvers
        val convertedManeuvers = ValhallaManeuverConverter.convertManeuvers(
            maneuvers = rawManeuvers,
            oldToNewIndexMap = simplification.oldToNewIndexMap
        )

        val simplifiedPoints = simplification.simplifiedPoints
        val pointCount = simplifiedPoints.size

        // 6. Final validation before serialization
        if (pointCount < 1 || pointCount > MAX_MOTONAV_POINTS) {
            throw IllegalStateException(
                "Route conversion validation failed: point count ($pointCount) must be between 1 and $MAX_MOTONAV_POINTS"
            )
        }

        val startMappedIdx = simplification.oldToNewIndexMap[0]
        if (startMappedIdx != 0 || simplifiedPoints.first() != decodedPoints.first()) {
            throw IllegalStateException("Route conversion validation failed: route start was not preserved at index 0")
        }

        val lastOriginalIndex = decodedPoints.size - 1
        val lastSimplifiedIndex = pointCount - 1
        val destMappedIdx = simplification.oldToNewIndexMap[lastOriginalIndex]
        if (destMappedIdx != lastSimplifiedIndex || simplifiedPoints.last() != decodedPoints.last()) {
            throw IllegalStateException("Route conversion validation failed: route destination was not preserved at last index")
        }

        for (rawM in convertedRawManeuvers) {
            val mappedIndex = simplification.oldToNewIndexMap[rawM.beginShapeIndex]
            if (mappedIndex == null || mappedIndex !in simplifiedPoints.indices) {
                throw IllegalStateException(
                    "Route conversion validation failed: converted maneuver at shape index ${rawM.beginShapeIndex} was not preserved in simplified points"
                )
            }
            if (simplifiedPoints[mappedIndex] != decodedPoints[rawM.beginShapeIndex]) {
                throw IllegalStateException(
                    "Route conversion validation failed: coordinates at mapped index $mappedIndex do not match original maneuver at ${rawM.beginShapeIndex}"
                )
            }
        }

        for (m in convertedManeuvers) {
            if (m.pointIndex !in simplifiedPoints.indices) {
                throw IllegalStateException(
                    "Route conversion validation failed: maneuver pointIndex ${m.pointIndex} out of bounds (0 until $pointCount)"
                )
            }
        }

        if (convertedManeuvers.size != convertedRawManeuvers.size) {
            throw IllegalStateException(
                "Route conversion validation failed: converted maneuvers count (${convertedManeuvers.size}) does not match expected (${convertedRawManeuvers.size})"
            )
        }

        // 7. Serialize to MotoNav Route Format v1 & calculate CRC32
        val serialized = MotoNavV1Serializer.serialize(
            routeId = routeId,
            points = simplifiedPoints,
            maneuvers = convertedManeuvers
        )

        // Extract metrics
        val summary = leg.summary ?: trip.summary
        val totalDistanceMeters = summary?.length
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { kotlin.math.round(it * 1000.0).toInt().coerceAtLeast(0) }
            ?: 0
        val durationSeconds = summary?.time
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { it.toInt().coerceAtLeast(0) }
            ?: 0

        return RouteConversionResult(
            serializedRoute = serialized,
            decodedPoints = decodedPoints,
            simplifiedPoints = simplifiedPoints,
            maneuvers = convertedManeuvers,
            totalDistanceMeters = totalDistanceMeters,
            durationSeconds = durationSeconds
        )
    }
}
