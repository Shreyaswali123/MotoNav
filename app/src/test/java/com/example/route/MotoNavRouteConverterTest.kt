package com.example.route

import com.example.model.RoutePoint
import com.example.network.ValhallaLeg
import com.example.network.ValhallaManeuver
import com.example.network.ValhallaRouteResponse
import com.example.network.ValhallaSummary
import com.example.network.ValhallaTrip
import com.example.ble.TestRouteFixture
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MotoNavRouteConverterTest {

    private fun parseFixture(): ValhallaRouteResponse {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(ValhallaRouteResponse::class.java)
        return adapter.fromJson(ValhallaRouteFixture.JSON_RESPONSE)
            ?: throw IllegalStateException("Failed to parse Valhalla JSON fixture")
    }

    private fun encodePolyline6(points: List<RoutePoint>): String {
        val result = StringBuilder()
        var lastLat = 0
        var lastLng = 0

        for (p in points) {
            val lat = Math.round(p.latitude * 1e6).toInt()
            val lng = Math.round(p.longitude * 1e6).toInt()

            encodeValue(lat - lastLat, result)
            encodeValue(lng - lastLng, result)

            lastLat = lat
            lastLng = lng
        }
        return result.toString()
    }

    private fun encodeValue(value: Int, result: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            result.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        result.append((v + 63).toChar())
    }

    private fun createSyntheticResponse(
        points: List<RoutePoint>,
        maneuvers: List<ValhallaManeuver>
    ): ValhallaRouteResponse {
        val shape = encodePolyline6(points)
        val leg = ValhallaLeg(
            shape = shape,
            summary = ValhallaSummary(
                length = (points.size * 100.0) / 1000.0,
                time = points.size * 5.0
            ),
            maneuvers = maneuvers
        )
        return ValhallaRouteResponse(
            trip = ValhallaTrip(
                legs = listOf(leg),
                summary = leg.summary
            )
        )
    }

    @Test
    fun testKnownRouteConversionProperties() {
        val response = parseFixture()
        assertNotNull(response.trip)
        val leg = response.trip!!.legs!!.first()

        // 1. Verify original raw attributes
        assertEquals("Known route must have 21 raw maneuver records", 21, leg.maneuvers?.size)

        // 2. Perform end-to-end conversion
        val result = MotoNavRouteConverter.convert(
            response = response,
            routeId = 1L,
            maxSpacingMeters = 60.0
        )

        // 3. Verify geometry point count before and after simplification
        assertEquals("Original decoded polyline must contain exactly 404 points", 404, result.decodedPoints.size)
        assertEquals("Simplified geometry must contain exactly 203 points with 60m spacing", 203, result.simplifiedPoints.size)

        // 4. Verify converted maneuver count (20 supported maneuvers, depart skipped)
        assertEquals("Converted maneuvers must contain exactly 20 supported MotoNav maneuvers", 20, result.maneuvers.size)

        // 5. Verify binary size formula: 9 + 203 * 8 + 20 * 7 = 1773 bytes
        val expectedBinarySize = 9 + (203 * 8) + (20 * 7)
        assertEquals("Binary size must be 1773 bytes", expectedBinarySize, result.serializedRoute.binary.size)
        assertEquals(1773, result.serializedRoute.binary.size)

        // 6. Verify binary header
        val buf = ByteBuffer.wrap(result.serializedRoute.binary).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("Version must be 1", 1.toByte(), buf.get())
        assertEquals("Route ID must be 1", 1, buf.int)
        assertEquals("Point count must be 203", 203.toShort(), buf.short)
        assertEquals("Maneuver count must be 20", 20.toShort(), buf.short)

        // 7. Verify start and destination coordinates
        val firstPt = result.simplifiedPoints.first()
        val lastPt = result.simplifiedPoints.last()
        assertEquals(15.3500, firstPt.latitude, 0.01)
        assertEquals(75.1491, firstPt.longitude, 0.01)
        assertEquals(15.44039, lastPt.latitude, 0.01)
        assertEquals(75.00455, lastPt.longitude, 0.01)

        // 8. Verify CRC32 is valid unsigned 32-bit integer
        assertTrue("CRC32 must be non-zero", result.serializedRoute.crc32 != 0L)
        println("Generated Route CRC32: 0x${result.serializedRoute.crc32.toString(16).uppercase()}")
        println("Generated Route Binary Size: ${result.serializedRoute.binary.size} bytes")
        println("Point count: ${result.simplifiedPoints.size}, Maneuver count: ${result.maneuvers.size}")
    }

    @Test
    fun testRouteWithMoreThan750PointsIsAdaptivelyReducedToAtMost750() {
        // Create 1500 points, each ~100m apart (total 150 km)
        // With 60m spacing, all 1500 points would originally be kept
        val points = (0..1500).map { i ->
            RoutePoint(15.0 + (i * 0.0009), 75.0)
        }

        // Add 4 converted maneuvers (types: 14=left, 10=right, 15=left, 4=destination)
        val maneuvers = listOf(
            ValhallaManeuver(type = 3, beginShapeIndex = 0, instruction = "Start"),
            ValhallaManeuver(type = 14, beginShapeIndex = 250, instruction = "Turn left", length = 2.5),
            ValhallaManeuver(type = 10, beginShapeIndex = 700, instruction = "Turn right", length = 5.0),
            ValhallaManeuver(type = 15, beginShapeIndex = 1100, instruction = "Turn sharp left", length = 3.2),
            ValhallaManeuver(type = 4, beginShapeIndex = 1500, instruction = "Destination reached", length = 1.0)
        )

        val response = createSyntheticResponse(points, maneuvers)
        val result = MotoNavRouteConverter.convert(response, routeId = 42L)

        // Must guarantee <= 750 points
        assertTrue(
            "Point count (${result.simplifiedPoints.size}) must be <= 750",
            result.simplifiedPoints.size <= 750
        )
        assertTrue(
            "Point count (${result.simplifiedPoints.size}) must be >= 1",
            result.simplifiedPoints.isNotEmpty()
        )

        // Start and destination preserved
        assertEquals(points.first().latitude, result.simplifiedPoints.first().latitude, 1e-6)
        assertEquals(points.first().longitude, result.simplifiedPoints.first().longitude, 1e-6)
        assertEquals(points.last().latitude, result.simplifiedPoints.last().latitude, 1e-6)
        assertEquals(points.last().longitude, result.simplifiedPoints.last().longitude, 1e-6)

        // All 4 converted maneuvers preserved with valid point indices
        assertEquals(4, result.maneuvers.size)
        val expectedShapeIndices = listOf(250, 700, 1100, 1500)
        for ((idx, expectedShapeIdx) in expectedShapeIndices.withIndex()) {
            val maneuver = result.maneuvers[idx]
            assertTrue(
                "Maneuver pointIndex ${maneuver.pointIndex} must be within bounds 0 until ${result.simplifiedPoints.size}",
                maneuver.pointIndex in result.simplifiedPoints.indices
            )
            val pointAtManeuver = result.simplifiedPoints[maneuver.pointIndex]
            val originalPoint = points[expectedShapeIdx]
            assertEquals(originalPoint.latitude, pointAtManeuver.latitude, 1e-6)
            assertEquals(originalPoint.longitude, pointAtManeuver.longitude, 1e-6)
        }

        // Serialized binary format validation
        val expectedSize = 9 + (result.simplifiedPoints.size * 8) + (result.maneuvers.size * 7)
        assertEquals(expectedSize, result.serializedRoute.binary.size)
        assertTrue(result.serializedRoute.crc32 != 0L)
    }

    @Test
    fun testRouteUnder750PointsHasNoUnnecessarySimplification() {
        val response = parseFixture()
        val result = MotoNavRouteConverter.convert(response, routeId = 1L, maxSpacingMeters = 60.0)

        // The known route has 404 points, which simplifies to 203 points at 60m.
        // Since 203 <= 750, it should not undergo any additional adaptive simplification.
        assertEquals(203, result.simplifiedPoints.size)
    }

    @Test
    fun testRouteWithMoreThan750MandatoryPointsFailsCleanly() {
        val points = (0..800).map { i ->
            RoutePoint(15.0 + (i * 0.0001), 75.0)
        }

        // Create 760 maneuvers with type 14 (LEFT) at unique shape indices
        val maneuvers = (1..760).map { i ->
            ValhallaManeuver(type = 14, beginShapeIndex = i, instruction = "Turn left $i")
        }

        val response = createSyntheticResponse(points, maneuvers)

        try {
            MotoNavRouteConverter.convert(response)
            fail("Should throw IllegalArgumentException when mandatory points exceed 750")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Error message should clearly explain mandatory points exceed MAX_MOTONAV_POINTS: ${e.message}",
                e.message?.contains("exceed MAX_MOTONAV_POINTS") == true
            )
        }
    }

    @Test
    fun testDeterminismConvertingSameResponseTwiceProducesIdenticalOutput() {
        val response = parseFixture()

        val run1 = MotoNavRouteConverter.convert(response, routeId = 100L)
        val run2 = MotoNavRouteConverter.convert(response, routeId = 100L)

        assertEquals(run1.simplifiedPoints.size, run2.simplifiedPoints.size)
        assertEquals(run1.simplifiedPoints, run2.simplifiedPoints)
        assertEquals(run1.maneuvers, run2.maneuvers)
        assertArrayEquals(run1.serializedRoute.binary, run2.serializedRoute.binary)
        assertEquals(run1.serializedRoute.crc32, run2.serializedRoute.crc32)

        // Also test determinism on adaptive route
        val points = (0..1200).map { i -> RoutePoint(15.0 + (i * 0.001), 75.0) }
        val adaptiveResp = createSyntheticResponse(points, emptyList())

        val adapt1 = MotoNavRouteConverter.convert(adaptiveResp, routeId = 101L)
        val adapt2 = MotoNavRouteConverter.convert(adaptiveResp, routeId = 101L)

        assertEquals(adapt1.simplifiedPoints.size, adapt2.simplifiedPoints.size)
        assertEquals(adapt1.simplifiedPoints, adapt2.simplifiedPoints)
        assertArrayEquals(adapt1.serializedRoute.binary, adapt2.serializedRoute.binary)
        assertEquals(adapt1.serializedRoute.crc32, adapt2.serializedRoute.crc32)
    }

    @Test
    fun testCanonical62ByteFixtureRemainsCompatible() {
        val binary = TestRouteFixture.createTestRouteBinary()
        assertEquals("Canonical test route fixture must remain exactly 62 bytes", 62, binary.size)
        assertEquals("CRC must remain exactly 0xDC0213B4", 0xDC0213B4L, TestRouteFixture.EXPECTED_CRC)
    }

    @Test
    fun testNormalPositiveSummaryValuesRemainUnchanged() {
        val points = listOf(RoutePoint(15.0, 75.0), RoutePoint(15.01, 75.01))
        val shape = encodePolyline6(points)
        val response = ValhallaRouteResponse(
            trip = ValhallaTrip(
                legs = listOf(
                    ValhallaLeg(
                        shape = shape,
                        summary = ValhallaSummary(length = 26.547, time = 3849.671)
                    )
                )
            )
        )
        val result = MotoNavRouteConverter.convert(response)
        assertEquals(26547, result.totalDistanceMeters)
        assertEquals(3849, result.durationSeconds)
    }

    @Test
    fun testNegativeSummaryMetricsFallBackToZero() {
        val points = listOf(RoutePoint(15.0, 75.0), RoutePoint(15.01, 75.01))
        val shape = encodePolyline6(points)
        val response = ValhallaRouteResponse(
            trip = ValhallaTrip(
                legs = listOf(
                    ValhallaLeg(
                        shape = shape,
                        summary = ValhallaSummary(length = -1.5, time = -120.0)
                    )
                )
            )
        )
        val result = MotoNavRouteConverter.convert(response)
        assertEquals(0, result.totalDistanceMeters)
        assertEquals(0, result.durationSeconds)
    }

    @Test
    fun testNaNSummaryMetricsFallBackToZero() {
        val points = listOf(RoutePoint(15.0, 75.0), RoutePoint(15.01, 75.01))
        val shape = encodePolyline6(points)
        val response = ValhallaRouteResponse(
            trip = ValhallaTrip(
                legs = listOf(
                    ValhallaLeg(
                        shape = shape,
                        summary = ValhallaSummary(length = Double.NaN, time = Double.NaN)
                    )
                )
            )
        )
        val result = MotoNavRouteConverter.convert(response)
        assertEquals(0, result.totalDistanceMeters)
        assertEquals(0, result.durationSeconds)
    }

    @Test
    fun testPositiveInfinitySummaryMetricsFallBackToZero() {
        val points = listOf(RoutePoint(15.0, 75.0), RoutePoint(15.01, 75.01))
        val shape = encodePolyline6(points)
        val response = ValhallaRouteResponse(
            trip = ValhallaTrip(
                legs = listOf(
                    ValhallaLeg(
                        shape = shape,
                        summary = ValhallaSummary(length = Double.POSITIVE_INFINITY, time = Double.POSITIVE_INFINITY)
                    )
                )
            )
        )
        val result = MotoNavRouteConverter.convert(response)
        assertEquals(0, result.totalDistanceMeters)
        assertEquals(0, result.durationSeconds)
    }

    @Test
    fun testNegativeInfinitySummaryMetricsFallBackToZero() {
        val points = listOf(RoutePoint(15.0, 75.0), RoutePoint(15.01, 75.01))
        val shape = encodePolyline6(points)
        val response = ValhallaRouteResponse(
            trip = ValhallaTrip(
                legs = listOf(
                    ValhallaLeg(
                        shape = shape,
                        summary = ValhallaSummary(length = Double.NEGATIVE_INFINITY, time = Double.NEGATIVE_INFINITY)
                    )
                )
            )
        )
        val result = MotoNavRouteConverter.convert(response)
        assertEquals(0, result.totalDistanceMeters)
        assertEquals(0, result.durationSeconds)
    }
}
