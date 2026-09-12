package com.example.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Exact MotoNav Route v1 binary format for ESP32 firmware integration testing.
 *
 * BINARY STRUCTURE (Total 62 bytes, little-endian):
 *
 * 1. HEADER = 9 bytes:
 *    - byte 0: version = 1 (uint8)
 *    - bytes 1-4: route ID = uint32 LE, value 1
 *    - bytes 5-6: point count = uint16 LE, value 4
 *    - bytes 7-8: maneuver count = uint16 LE, value 3
 *
 * 2. POINTS = 32 bytes (4 points × 8 bytes):
 *    - bytes 0-3: latitude = signed int32 LE, decimal degrees × 1e7
 *    - bytes 4-7: longitude = signed int32 LE, decimal degrees × 1e7
 *    - Point 0: lat 15.3647000 (153647000), lon 75.1240000 (751240000)
 *    - Point 1: lat 15.3658000 (153658000), lon 75.1262000 (751262000)
 *    - Point 2: lat 15.3681000 (153681000), lon 75.1294000 (751294000)
 *    - Point 3: lat 15.3700000 (153700000), lon 75.1320000 (751320000)
 *
 * 3. MANEUVERS = 21 bytes (3 maneuvers × 7 bytes):
 *    - bytes 0-1: point index = uint16 LE
 *    - byte 2: maneuver type = uint8
 *    - bytes 3-6: distance = uint32 LE
 *    - Maneuver 0: point index 1, type 2 (RIGHT), distance 250
 *    - Maneuver 1: point index 2, type 1 (LEFT), distance 180
 *    - Maneuver 2: point index 3, type 5 (DESTINATION), distance 0
 *
 * Expected Binary Size: 62 bytes
 * Expected CRC32: 0xDC0213B4
 */
data class MotoNavRouteV1Header(
    val version: Byte = 1,
    val routeId: Int = 1,
    val pointCount: Short = 4,
    val maneuverCount: Short = 3
)

data class MotoNavRouteV1Point(
    val latitude: Double,
    val longitude: Double
) {
    val latE7: Int get() = (latitude * 1e7).toInt()
    val lonE7: Int get() = (longitude * 1e7).toInt()
}

data class MotoNavRouteV1Maneuver(
    val pointIndex: Short,
    val type: Byte,
    val distance: Int
)

object TestRouteFixture {

    const val EXPECTED_SIZE = 62
    const val EXPECTED_CRC: Long = 0xDC0213B4L

    // Default chunk payload size for ROUTE_DATA packet payload.
    // 20 bytes allows packet (6 header + 20 payload + 4 crc = 30 bytes)
    // to cleanly fragment 62 bytes into 4 packets: [20, 20, 20, 2].
    const val DEFAULT_PAYLOAD_CHUNK_SIZE = 20

    /**
     * Canonical hexadecimal reference for MotoNav Route v1 test route.
     */
    const val HEX_REFERENCE =
        "01 01 00 00 00 04 00 03 00 " +
        "98 77 28 09 40 03 C7 2C " +
        "90 A2 28 09 30 59 C7 2C " +
        "68 FC 28 09 30 D6 C7 2C " +
        "A0 46 29 09 C0 3B C8 2C " +
        "01 00 02 FA 00 00 00 " +
        "02 00 01 B4 00 00 00 " +
        "03 00 05 00 00 00 00"

    val TEST_HEADER = MotoNavRouteV1Header()

    val TEST_POINTS = listOf(
        MotoNavRouteV1Point(15.3647000, 75.1240000),
        MotoNavRouteV1Point(15.3658000, 75.1262000),
        MotoNavRouteV1Point(15.3681000, 75.1294000),
        MotoNavRouteV1Point(15.3700000, 75.1320000)
    )

    val TEST_MANEUVERS = listOf(
        MotoNavRouteV1Maneuver(pointIndex = 1, type = 2, distance = 250),
        MotoNavRouteV1Maneuver(pointIndex = 2, type = 1, distance = 180),
        MotoNavRouteV1Maneuver(pointIndex = 3, type = 5, distance = 0)
    )

    /**
     * Constructs the exact 62-byte binary representation of the test route.
     * All multi-byte integers are strictly little-endian.
     */
    fun createTestRouteBinary(): ByteArray {
        val buffer = ByteBuffer.allocate(EXPECTED_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Header (9 bytes)
        buffer.put(TEST_HEADER.version)                  // byte 0: version = 1
        buffer.putInt(TEST_HEADER.routeId)               // bytes 1-4: route ID uint32 LE = 1
        buffer.putShort(TEST_HEADER.pointCount)          // bytes 5-6: point count uint16 LE = 4
        buffer.putShort(TEST_HEADER.maneuverCount)       // bytes 7-8: maneuver count uint16 LE = 3

        // 2. Points (4 points × 8 bytes = 32 bytes)
        for (point in TEST_POINTS) {
            buffer.putInt(point.latE7)                   // bytes 0-3: latitude signed int32 LE (deg * 1e7)
            buffer.putInt(point.lonE7)                   // bytes 4-7: longitude signed int32 LE (deg * 1e7)
        }

        // 3. Maneuvers (3 maneuvers × 7 bytes = 21 bytes)
        for (maneuver in TEST_MANEUVERS) {
            buffer.putShort(maneuver.pointIndex)         // bytes 0-1: point index uint16 LE
            buffer.put(maneuver.type)                    // byte 2: maneuver type uint8
            buffer.putInt(maneuver.distance)             // bytes 3-6: distance uint32 LE
        }

        return buffer.array()
    }

    /**
     * Converts a byte array into a hex string matching HEX_REFERENCE spacing.
     */
    fun toHexString(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    /**
     * Calculates the standard zlib-compatible CRC32 over the given byte array.
     */
    fun calculateCrc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Long {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value and 0xFFFFFFFFL
    }

    /**
     * Builds START_ROUTE control command:
     * 0x01 + uint32 little-endian expected route size
     */
    fun buildStartRouteCommand(expectedSize: Int = EXPECTED_SIZE): ByteArray {
        require(expectedSize >= 0) { "expectedSize cannot be negative, got $expectedSize" }
        val buffer = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x01.toByte())
        buffer.putInt(expectedSize)
        return buffer.array()
    }

    /**
     * Builds END_ROUTE control command:
     * 0x02 + uint32 little-endian complete-route CRC32
     */
    fun buildEndRouteCommand(crc32: Long = EXPECTED_CRC): ByteArray {
        val buffer = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x02.toByte())
        buffer.putInt((crc32 and 0xFFFFFFFFL).toInt())
        return buffer.array()
    }

    /**
     * Frames a ROUTE_DATA packet according to protocol:
     * offset 0: type uint8 = 0x03
     * offset 1: sequence uint16 LE
     * offset 3: payload length uint16 LE
     * offset 5: flags uint8 = 0x00
     * offset 6: payload (payload length bytes)
     * after payload: packet CRC32 uint32 LE (calculated over header + payload)
     */
    fun frameRouteDataPacket(sequence: Int, payload: ByteArray): ByteArray {
        require(sequence in 0..65535) { "sequence must be in range 0..65535, got $sequence" }
        require(payload.size in 0..65535) { "payload.size must be in range 0..65535, got ${payload.size}" }

        val headerSize = 6
        val crcSize = 4
        val totalSize = headerSize + payload.size + crcSize

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x03.toByte())                   // type uint8
        buffer.putShort((sequence and 0xFFFF).toShort())        // sequence uint16 LE
        buffer.putShort((payload.size and 0xFFFF).toShort())    // payload length uint16 LE
        buffer.put(0x00.toByte())                  // flags uint8

        buffer.put(payload)                        // payload bytes

        // Calculate CRC32 over header + payload (bytes 0 until headerSize + payload.size)
        val packetCrc = calculateCrc32(buffer.array(), 0, headerSize + payload.size)
        buffer.putInt((packetCrc and 0xFFFFFFFFL).toInt()) // packet CRC32 uint32 LE

        return buffer.array()
    }

    /**
     * Splits a binary route into a list of framed ROUTE_DATA packets.
     */
    fun createRouteDataPackets(
        routeBinary: ByteArray = createTestRouteBinary(),
        chunkSize: Int = DEFAULT_PAYLOAD_CHUNK_SIZE
    ): List<FramedPacket> {
        require(chunkSize > 0) { "chunkSize must be greater than 0, got $chunkSize" }
        val packets = mutableListOf<FramedPacket>()
        var offset = 0
        var sequence = 0

        while (offset < routeBinary.size) {
            val length = minOf(chunkSize, routeBinary.size - offset)
            val chunk = routeBinary.copyOfRange(offset, offset + length)
            val framed = frameRouteDataPacket(sequence, chunk)
            packets.add(
                FramedPacket(
                    sequence = sequence,
                    payloadOffset = offset,
                    payloadLength = length,
                    payload = chunk,
                    framedBytes = framed
                )
            )
            offset += length
            sequence++
        }
        return packets
    }
}

data class FramedPacket(
    val sequence: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
    val payload: ByteArray,
    val framedBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FramedPacket
        if (sequence != other.sequence) return false
        if (payloadOffset != other.payloadOffset) return false
        if (payloadLength != other.payloadLength) return false
        if (!payload.contentEquals(other.payload)) return false
        if (!framedBytes.contentEquals(other.framedBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sequence
        result = 31 * result + payloadOffset
        result = 31 * result + payloadLength
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + framedBytes.contentHashCode()
        return result
    }
}
