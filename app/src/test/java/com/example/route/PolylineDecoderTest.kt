package com.example.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PolylineDecoderTest {

    private fun encodeValue(v: Int): String {
        val result = StringBuilder()
        var num = if (v < 0) (v shl 1).inv() else (v shl 1)
        while (num >= 0x20) {
            val nextVal = (0x20 or (num and 0x1f)) + 63
            result.append(nextVal.toChar())
            num = num shr 5
        }
        num += 63
        result.append(num.toChar())
        return result.toString()
    }

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

    // 1. Truncated Varints
    @Test
    fun testSingleContinuationCharacterFails() {
        try {
            PolylineDecoder.decodePolyline6("_")
            fail("Expected IllegalArgumentException for single continuation character")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("truncated varint") == true)
        }

        try {
            PolylineDecoder.decodePolyline6("~")
            fail("Expected IllegalArgumentException for single continuation character")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("truncated varint") == true)
        }
    }

    @Test
    fun testTruncatedLatitudeFails() {
        // "_izl" has continuation bits set on all characters without terminating chunk
        try {
            PolylineDecoder.decodePolyline6("_izl")
            fail("Expected IllegalArgumentException for truncated latitude")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("truncated varint") == true)
        }
    }

    @Test
    fun testTruncatedLongitudeFails() {
        // "_izlhA" is valid latitude, followed by "~" which is a continuation character without terminal chunk
        try {
            PolylineDecoder.decodePolyline6("_izlhA~")
            fail("Expected IllegalArgumentException for truncated longitude")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("truncated varint") == true)
        }
    }

    // 2. Incomplete Coordinate Pairs
    @Test
    fun testCompleteLatitudeWithNoLongitudeFails() {
        // "_izlhA" is a complete latitude with no longitude characters following
        try {
            PolylineDecoder.decodePolyline6("_izlhA")
            fail("Expected IllegalArgumentException for complete latitude with missing longitude")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("incomplete coordinate pair") == true)
        }
    }

    @Test
    fun testCompleteLatitudeFollowedByIncompleteLongitudeFails() {
        try {
            PolylineDecoder.decodePolyline6("_izlhA~r")
            fail("Expected IllegalArgumentException for complete latitude followed by incomplete longitude")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("truncated varint") == true)
        }
    }

    // 3. Excessive Shifts / Integer Wrapping
    @Test
    fun testExcessiveContinuationSequenceFails() {
        // 10 continuation characters exceeds 32 bits / shift > 30
        try {
            PolylineDecoder.decodePolyline6("~~~~~~~~~~")
            fail("Expected IllegalArgumentException for excessive continuation sequence")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("varint exceeds 32 bits") == true)
        }
    }

    // 4. Invalid Characters
    @Test
    fun testAsciiBelow63Fails() {
        try {
            PolylineDecoder.decodePolyline6("0")
            fail("Expected IllegalArgumentException for ASCII below 63")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid character") == true)
        }

        try {
            PolylineDecoder.decodePolyline6("_izlhA0~rlgdF")
            fail("Expected IllegalArgumentException for ASCII below 63 embedded in stream")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid character") == true)
        }
    }

    @Test
    fun testSpaceFails() {
        try {
            PolylineDecoder.decodePolyline6("_izlhA ~rlgdF")
            fail("Expected IllegalArgumentException for space character in polyline")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid character") == true)
        }
    }

    @Test
    fun testControlCharacterFails() {
        try {
            PolylineDecoder.decodePolyline6("_izlhA\u0001~rlgdF")
            fail("Expected IllegalArgumentException for control character")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid character") == true)
        }
    }

    @Test
    fun testNewlineAndTabFail() {
        try {
            PolylineDecoder.decodePolyline6("_izlhA\n~rlgdF")
            fail("Expected IllegalArgumentException for newline")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid character") == true)
        }

        try {
            PolylineDecoder.decodePolyline6("_izlhA\t~rlgdF")
            fail("Expected IllegalArgumentException for tab")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid character") == true)
        }
    }

    @Test
    fun testUnicodeCharacterOutsideValidAsciiRangeFails() {
        try {
            PolylineDecoder.decodePolyline6("\u00E9")
            fail("Expected IllegalArgumentException for Unicode character")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid character") == true)
        }

        try {
            PolylineDecoder.decodePolyline6("_izlhA\u00E9~rlgdF")
            fail("Expected IllegalArgumentException for Unicode character")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid character") == true)
        }
    }

    // 5. Geographic Bounds
    @Test
    fun testDecodedLatitudeGreaterThan90Fails() {
        val poly = encodeValue(91_000_000) + encodeValue(0)
        try {
            PolylineDecoder.decodePolyline6(poly)
            fail("Expected IllegalArgumentException for latitude > 90.0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("latitude out of range") == true)
        }
    }

    @Test
    fun testDecodedLatitudeLessThanMinus90Fails() {
        val poly = encodeValue(-91_000_000) + encodeValue(0)
        try {
            PolylineDecoder.decodePolyline6(poly)
            fail("Expected IllegalArgumentException for latitude < -90.0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("latitude out of range") == true)
        }
    }

    @Test
    fun testDecodedLongitudeGreaterThan180Fails() {
        val poly = encodeValue(0) + encodeValue(181_000_000)
        try {
            PolylineDecoder.decodePolyline6(poly)
            fail("Expected IllegalArgumentException for longitude > 180.0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("longitude out of range") == true)
        }
    }

    @Test
    fun testDecodedLongitudeLessThanMinus180Fails() {
        val poly = encodeValue(0) + encodeValue(-181_000_000)
        try {
            PolylineDecoder.decodePolyline6(poly)
            fail("Expected IllegalArgumentException for longitude < -180.0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("longitude out of range") == true)
        }
    }
}
