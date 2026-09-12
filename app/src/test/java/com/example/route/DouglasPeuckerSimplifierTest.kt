package com.example.route

import com.example.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DouglasPeuckerSimplifierTest {

    @Test
    fun testEmptyInputReturnsEmptyResult() {
        val res = DouglasPeuckerSimplifier.simplify(emptyList())
        assertTrue(res.simplifiedPoints.isEmpty())
        assertTrue(res.oldToNewIndexMap.isEmpty())
    }

    @Test
    fun testTwoPointsPreserved() {
        val pts = listOf(
            RoutePoint(15.350, 75.149),
            RoutePoint(15.351, 75.150)
        )
        val res = DouglasPeuckerSimplifier.simplify(pts)
        assertEquals(2, res.simplifiedPoints.size)
        assertEquals(0, res.oldToNewIndexMap[0])
        assertEquals(1, res.oldToNewIndexMap[1])
    }

    @Test
    fun testMandatoryPointsAreStrictlyPreserved() {
        // Create 20 closely-spaced points (e.g. 5 meters apart)
        val pts = (0..20).map { i ->
            RoutePoint(15.3500 + (i * 0.00005), 75.1490)
        }
        val mandatory = setOf(5, 12, 17)

        val res = DouglasPeuckerSimplifier.simplify(
            points = pts,
            mandatoryIndices = mandatory,
            maxSpacingMeters = 60.0
        )

        // Verify index 0 and index 20 (start and destination) are preserved
        assertTrue(res.oldToNewIndexMap.containsKey(0))
        assertTrue(res.oldToNewIndexMap.containsKey(20))

        // Verify every mandatory index is present in the mapping
        for (mIdx in mandatory) {
            assertTrue("Mandatory index $mIdx must be preserved", res.oldToNewIndexMap.containsKey(mIdx))
            val newIdx = res.oldToNewIndexMap[mIdx]!!
            assertEquals(pts[mIdx].latitude, res.simplifiedPoints[newIdx].latitude, 1e-6)
            assertEquals(pts[mIdx].longitude, res.simplifiedPoints[newIdx].longitude, 1e-6)
        }
    }

    @Test
    fun testSpacingReducesDensity() {
        // Line of 100 points, each ~10 meters apart (total ~1000m)
        val pts = (0..100).map { i ->
            RoutePoint(15.0 + (i * 0.00009), 75.0)
        }

        val res = DouglasPeuckerSimplifier.simplify(
            points = pts,
            mandatoryIndices = emptySet(),
            maxSpacingMeters = 60.0
        )

        // 1000 meters / 60 meters ~ 17-20 points
        assertTrue(res.simplifiedPoints.size < 30)
        assertTrue(res.simplifiedPoints.size > 10)
        assertEquals(0, res.oldToNewIndexMap[0])
        assertEquals(res.simplifiedPoints.size - 1, res.oldToNewIndexMap[100])
    }

    @Test
    fun testAdaptiveSimplificationReducesPointsBelow750WhilePreservingMandatory() {
        // 1500 points, each ~100 meters apart (total ~150km)
        // With 60m spacing, all 1500 points would be kept because distance (100m) > 60m
        val pts = (0..1500).map { i ->
            RoutePoint(15.0 + (i * 0.0009), 75.0)
        }
        val mandatory = setOf(100, 500, 1000)

        val res = DouglasPeuckerSimplifier.simplifyAdaptive(
            points = pts,
            mandatoryIndices = mandatory,
            initialSpacingMeters = 60.0,
            maxPoints = 750
        )

        // Must be <= 750 points
        assertTrue("Simplified points must be <= 750, was ${res.simplifiedPoints.size}", res.simplifiedPoints.size <= 750)
        assertTrue("Simplified points must be >= 1", res.simplifiedPoints.isNotEmpty())

        // Start and destination preserved
        assertEquals(0, res.oldToNewIndexMap[0])
        assertEquals(res.simplifiedPoints.size - 1, res.oldToNewIndexMap[1500])

        // Mandatory points strictly preserved
        for (mIdx in mandatory) {
            assertTrue("Mandatory index $mIdx must be preserved", res.oldToNewIndexMap.containsKey(mIdx))
            val newIdx = res.oldToNewIndexMap[mIdx]!!
            assertEquals(pts[mIdx].latitude, res.simplifiedPoints[newIdx].latitude, 1e-6)
            assertEquals(pts[mIdx].longitude, res.simplifiedPoints[newIdx].longitude, 1e-6)
        }
    }

    @Test
    fun testAdaptiveSimplificationThrowsWhenMandatoryPointsExceedMax() {
        val pts = (0..800).map { i ->
            RoutePoint(15.0 + (i * 0.0001), 75.0)
        }
        val mandatory = (1..760).toSet()

        var caught: IllegalArgumentException? = null
        try {
            DouglasPeuckerSimplifier.simplifyAdaptive(
                points = pts,
                mandatoryIndices = mandatory,
                maxPoints = 750
            )
        } catch (e: IllegalArgumentException) {
            caught = e
        }

        org.junit.Assert.assertNotNull("Should throw IllegalArgumentException when mandatory points > 750", caught)
        assertTrue(
            "Error message must explain mandatory points exceed limit: ${caught?.message}",
            caught?.message?.contains("exceed MAX_MOTONAV_POINTS") == true
        )
    }

    @Test
    fun testAdaptiveSimplificationUnderLimitDoesNoExtraSimplification() {
        // Line of 100 points
        val pts = (0..100).map { i ->
            RoutePoint(15.0 + (i * 0.00009), 75.0)
        }
        val mandatory = setOf(20, 50, 80)

        val normal = DouglasPeuckerSimplifier.simplify(pts, mandatory, 60.0)
        val adaptive = DouglasPeuckerSimplifier.simplifyAdaptive(pts, mandatory, 60.0, 750)

        assertEquals(normal.simplifiedPoints.size, adaptive.simplifiedPoints.size)
        assertEquals(normal.simplifiedPoints, adaptive.simplifiedPoints)
        assertEquals(normal.oldToNewIndexMap, adaptive.oldToNewIndexMap)
    }

    @Test
    fun testDistanceMetersExactAntipodalPointsDoesNotProduceNaN() {
        val p1 = RoutePoint(0.0, 0.0)
        val p2 = RoutePoint(0.0, 180.0)
        val dist = DouglasPeuckerSimplifier.distanceMeters(p1, p2)

        assertTrue("Antipodal distance must be finite", dist.isFinite())
        assertTrue("Antipodal distance must not be NaN", !dist.isNaN())
        val expectedHalfCircumference = Math.PI * 6371000.0
        assertEquals(expectedHalfCircumference, dist, 100.0)

        val p3 = RoutePoint(-45.0, 0.0)
        val p4 = RoutePoint(45.0, 180.0)
        val dist2 = DouglasPeuckerSimplifier.distanceMeters(p3, p4)
        assertTrue("Antipodal distance 2 must be finite", dist2.isFinite())
        assertTrue("Antipodal distance 2 must not be NaN", !dist2.isNaN())
        assertEquals(expectedHalfCircumference, dist2, 100.0)
    }

    @Test
    fun testDistanceMetersNearAntipodalPointsDoesNotProduceNaN() {
        val p1 = RoutePoint(0.0, 0.0)
        val p2 = RoutePoint(0.0, 179.999999)
        val dist = DouglasPeuckerSimplifier.distanceMeters(p1, p2)

        assertTrue("Near-antipodal distance must be finite", dist.isFinite())
        assertTrue("Near-antipodal distance must not be NaN", !dist.isNaN())
        assertTrue("Near-antipodal distance must be > 20,000 km", dist > 20_000_000.0)
    }

    @Test
    fun testDistanceMetersExtremePolarCoordinatesDoesNotProduceNaN() {
        val northPole = RoutePoint(90.0, 0.0)
        val southPole = RoutePoint(-90.0, 0.0)
        val polarDist = DouglasPeuckerSimplifier.distanceMeters(northPole, southPole)

        assertTrue("Polar distance must be finite", polarDist.isFinite())
        assertTrue("Polar distance must not be NaN", !polarDist.isNaN())
        val expectedHalfCircumference = Math.PI * 6371000.0
        assertEquals(expectedHalfCircumference, polarDist, 100.0)

        // North pole to North pole with different longitudes
        val np1 = RoutePoint(90.0, 0.0)
        val np2 = RoutePoint(90.0, 180.0)
        val zeroDist = DouglasPeuckerSimplifier.distanceMeters(np1, np2)
        assertTrue("Distance between pole representations must be finite", zeroDist.isFinite())
        assertTrue("Distance between pole representations must not be NaN", !zeroDist.isNaN())
        assertEquals(0.0, zeroDist, 1e-3)
    }
}
