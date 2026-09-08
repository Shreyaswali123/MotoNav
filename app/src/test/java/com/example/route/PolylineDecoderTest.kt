package com.example.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineDecoderTest {

    @Test
    fun testDecodeEmptyOrBlankReturnsEmptyList() {
        assertTrue(PolylineDecoder.decodePolyline6("").isEmpty())
        assertTrue(PolylineDecoder.decodePolyline6("   ").isEmpty())
    }

    @Test
    fun testDecodeSingleCoordinate() {
        // (38.5, -120.2) encoded with 1e6 precision factor
        val points = PolylineDecoder.decodePolyline6("_izlhA~rlgdF")
        assertEquals(1, points.size)
        assertEquals(38.5, points[0].latitude, 0.000001)
        assertEquals(-120.2, points[0].longitude, 0.000001)
    }

    @Test
    fun testDecodeMultiplePoints() {
        // Two points: (15.350000, 75.149100) and (15.440390, 75.004550) encoded with 1e6
        val points = PolylineDecoder.decodePolyline6("_n{g\\wqvinCkpoDjiyG")
        assertEquals(2, points.size)
        assertEquals(15.350000, points[0].latitude, 0.000001)
        assertEquals(75.149100, points[0].longitude, 0.000001)
        assertEquals(15.440390, points[1].latitude, 0.000001)
        assertEquals(75.004550, points[1].longitude, 0.000001)
    }

    @Test
    fun testPrecisionIsSixDecimalPlaces() {
        // Ensure values are divided by 1e6 and not 1e5
        val points = PolylineDecoder.decodePolyline6("_izlhA~rlgdF")
        assertEquals(38.5, points[0].latitude, 1e-6)
    }
}
