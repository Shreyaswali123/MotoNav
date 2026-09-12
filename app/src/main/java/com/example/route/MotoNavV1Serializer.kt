package com.example.route

import com.example.model.RoutePoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Output of the MotoNav Route Format v1 serialization.
 *
 * @property binary The complete serialized binary payload.
 * @property crc32 Unsigned 32-bit CRC calculated over the entire [binary].
 */
data class SerializedMotoNavRoute(
    val binary: ByteArray,
    val crc32: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SerializedMotoNavRoute
        if (!binary.contentEquals(other.binary)) return false
        if (crc32 != other.crc32) return false
        return true
    }

    override fun hashCode(): Int {
        var result = binary.contentHashCode()
        result = 31 * result + crc32.hashCode()
        return result
    }
}

/**
 * Serializer for MotoNav Route Format v1.
 *
 * Format Specification:
 * - Header (9 bytes):
 *   - byte 0: version = 1 (uint8)
 *   - bytes 1-4: route ID (uint32 little-endian)
 *   - bytes 5-6: point count (uint16 little-endian)
 *   - bytes 7-8: maneuver count (uint16 little-endian)
 * - Points (8 bytes each):
 *   - bytes 0-3: latitude * 1e7 (int32 little-endian)
 *   - bytes 4-7: longitude * 1e7 (int32 little-endian)
 * - Maneuvers (7 bytes each):
 *   - bytes 0-1: point index (uint16 little-endian)
 *   - byte 2: type (uint8, only 1 = LEFT, 2 = RIGHT, 5 = DESTINATION)
 *   - bytes 3-6: distance in meters (uint32 little-endian)
 */
object MotoNavV1Serializer {

    const val VERSION_V1: Byte = 1
    val VALID_MANEUVER_TYPES = setOf<Byte>(1, 2, 5)

    /**
     * Serializes a route into MotoNav Format v1 and computes its CRC32 checksum.
     *
     * @param routeId Arbitrary identifier for the route.
     * @param points List of geographic coordinates.
     * @param maneuvers List of converted MotoNav maneuvers.
     * @return [SerializedMotoNavRoute] containing the exact byte array and CRC32.
     */
    fun serialize(
        routeId: Long = 1L,
        points: List<RoutePoint>,
        maneuvers: List<MotoNavManeuverRecord>
    ): SerializedMotoNavRoute {
        require(points.isNotEmpty()) { "Route must contain at least 1 point" }
        require(points.size <= 65535) { "Point count (${points.size}) exceeds uint16 maximum (65535)" }
        require(maneuvers.size <= 65535) { "Maneuver count (${maneuvers.size}) exceeds uint16 maximum (65535)" }

        val totalSize = 9 + (points.size * 8) + (maneuvers.size * 7)
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Header (9 bytes)
        buffer.put(VERSION_V1)
        buffer.putInt((routeId and 0xFFFFFFFFL).toInt())
        buffer.putShort((points.size and 0xFFFF).toShort())
        buffer.putShort((maneuvers.size and 0xFFFF).toShort())

        // 2. Points (8 bytes each: int32 lat*1e7, int32 lon*1e7)
        for ((idx, pt) in points.withIndex()) {
            require(pt.latitude in -90.0..90.0) {
                "Point $idx latitude ${pt.latitude} out of range [-90, 90]"
            }
            require(pt.longitude in -180.0..180.0) {
                "Point $idx longitude ${pt.longitude} out of range [-180, 180]"
            }

            val latScaled = Math.round(pt.latitude * 1e7)
            val lonScaled = Math.round(pt.longitude * 1e7)

            require(latScaled in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "Point $idx latitude does not fit in signed 32-bit int"
            }
            require(lonScaled in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "Point $idx longitude does not fit in signed 32-bit int"
            }

            buffer.putInt(latScaled.toInt())
            buffer.putInt(lonScaled.toInt())
        }

        // 3. Maneuvers (7 bytes each: uint16 pt_idx, uint8 type, uint32 distance)
        for ((idx, m) in maneuvers.withIndex()) {
            require(m.pointIndex in points.indices) {
                "Maneuver $idx references invalid pointIndex ${m.pointIndex} (points range: 0 until ${points.size})"
            }
            require(m.motoNavType in VALID_MANEUVER_TYPES) {
                "Maneuver $idx has unsupported MotoNav binary type: ${m.motoNavType}. Only types 1 (LEFT), 2 (RIGHT), 5 (DESTINATION) allowed."
            }
            require(m.distanceMeters >= 0) {
                "Maneuver $idx distance ${m.distanceMeters} must be non-negative"
            }
        }

        for (i in 1 until maneuvers.size) {
            require(maneuvers[i].pointIndex >= maneuvers[i - 1].pointIndex) {
                "Maneuver $i pointIndex (${maneuvers[i].pointIndex}) is less than previous maneuver (${maneuvers[i - 1].pointIndex})"
            }
        }

        for (m in maneuvers) {
            buffer.putShort((m.pointIndex and 0xFFFF).toShort())
            buffer.put(m.motoNavType)
            buffer.putInt(m.distanceMeters)
        }

        val binary = buffer.array()

        // 4. Calculate standard zlib-compatible CRC32 over the complete binary
        val crc = CRC32()
        crc.update(binary)
        val crc32 = crc.value and 0xFFFFFFFFL

        return SerializedMotoNavRoute(
            binary = binary,
            crc32 = crc32
        )
    }
}
