package com.example.route

import com.example.ble.TestRouteFixture
import com.example.model.RoutePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MotoNavV1SerializerTest {

    @Test
    fun testIndependentlyReproduceCanonical62ByteFixture() {
        // Points and maneuvers matching TestRouteFixture
        val points = listOf(
            RoutePoint(15.3647000, 75.1240000),
            RoutePoint(15.3658000, 75.1262000),
            RoutePoint(15.3681000, 75.1294000),
            RoutePoint(15.3700000, 75.1320000)
        )
        val maneuvers = listOf(
            MotoNavManeuverRecord(pointIndex = 1, motoNavType = 2, distanceMeters = 250, valhallaType = 10),
            MotoNavManeuverRecord(pointIndex = 2, motoNavType = 1, distanceMeters = 180, valhallaType = 15),
            MotoNavManeuverRecord(pointIndex = 3, motoNavType = 5, distanceMeters = 0, valhallaType = 4)
        )

        val result = MotoNavV1Serializer.serialize(
            routeId = 1L,
            points = points,
            maneuvers = maneuvers
        )

        // 1. Verify size is exactly 62 bytes
        assertEquals("Binary size must be exactly 62 bytes", 62, result.binary.size)

        // 2. Verify CRC32 is exactly 0xDC0213B4
        val expectedCrc = 0xDC0213B4L
        assertEquals(
            "CRC32 must match canonical 0xDC0213B4",
            expectedCrc,
            result.crc32
        )

        // 3. Verify byte-for-byte identity with canonical TestRouteFixture
        val canonicalBinary = TestRouteFixture.createTestRouteBinary()
        assertArrayEquals(
            "Serialized binary must be byte-for-byte identical to canonical fixture",
            canonicalBinary,
            result.binary
        )
    }

    @Test
    fun testHeaderAndLittleEndianFieldEncoding() {
        val points = listOf(
            RoutePoint(10.0, 20.0),
            RoutePoint(10.1, 20.1)
        )
        val maneuvers = listOf(
            MotoNavManeuverRecord(pointIndex = 1, motoNavType = 5, distanceMeters = 100, valhallaType = 4)
        )

        val result = MotoNavV1Serializer.serialize(
            routeId = 0x12345678L,
            points = points,
            maneuvers = maneuvers
        )

        assertEquals(9 + 2 * 8 + 1 * 7, result.binary.size)

        val buf = ByteBuffer.wrap(result.binary).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1.toByte(), buf.get()) // version
        assertEquals(0x12345678, buf.int) // routeId
        assertEquals(2.toShort(), buf.short) // pointCount
        assertEquals(1.toShort(), buf.short) // maneuverCount

        // Point 0
        assertEquals(100000000, buf.int) // 10.0 * 1e7
        assertEquals(200000000, buf.int) // 20.0 * 1e7

        // Point 1
        assertEquals(101000000, buf.int) // 10.1 * 1e7
        assertEquals(201000000, buf.int) // 20.1 * 1e7

        // Maneuver
        assertEquals(1.toShort(), buf.short) // pointIndex
        assertEquals(5.toByte(), buf.get()) // type
        assertEquals(100, buf.int) // distance
    }

    @Test
    fun testRejectsInvalidLatitude() {
        val points = listOf(RoutePoint(95.0, 75.0))
        assertThrows(IllegalArgumentException::class.java) {
            MotoNavV1Serializer.serialize(1L, points, emptyList())
        }
    }

    @Test
    fun testRejectsInvalidLongitude() {
        val points = listOf(RoutePoint(15.0, 195.0))
        assertThrows(IllegalArgumentException::class.java) {
            MotoNavV1Serializer.serialize(1L, points, emptyList())
        }
    }

    @Test
    fun testRejectsInvalidManeuverType() {
        val points = listOf(RoutePoint(15.0, 75.0), RoutePoint(15.1, 75.1))
        val invalidManeuvers = listOf(
            MotoNavManeuverRecord(pointIndex = 1, motoNavType = 3, distanceMeters = 50, valhallaType = 3)
        )
        assertThrows(IllegalArgumentException::class.java) {
            MotoNavV1Serializer.serialize(1L, points, invalidManeuvers)
        }
    }

    @Test
    fun testRejectsManeuverPointIndexOutOfRange() {
        val points = listOf(RoutePoint(15.0, 75.0))
        val invalidManeuvers = listOf(
            MotoNavManeuverRecord(pointIndex = 5, motoNavType = 5, distanceMeters = 0, valhallaType = 4)
        )
        assertThrows(IllegalArgumentException::class.java) {
            MotoNavV1Serializer.serialize(1L, points, invalidManeuvers)
        }
    }
}
