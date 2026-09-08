package com.example.ui.components

import com.example.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RouteMapAdapterTest {

    @Test
    fun testTwoValidRoutePointsProduceLineStringWithTwoCoordinates() {
        val points = listOf(
            RoutePoint(latitude = 15.3647, longitude = 75.1240),
            RoutePoint(latitude = 15.4000, longitude = 75.2000)
        )
        val lineString = RouteMapAdapter.toLineString(points)

        assertNotNull("LineString should be created for two valid points", lineString)
        assertEquals(2, lineString!!.coordinates.size)
    }

    @Test
    fun testCoordinateOrderingIsLongitudeThenLatitude() {
        val point1 = RoutePoint(latitude = 15.3647, longitude = 75.1240)
        val point2 = RoutePoint(latitude = 15.4000, longitude = 75.2000)
        val lineString = RouteMapAdapter.toLineString(listOf(point1, point2))

        assertNotNull(lineString)
        val pos1 = lineString!!.coordinates[0]
        // GeoJSON coordinate order must be longitude first, latitude second
        assertEquals(75.1240, pos1.longitude, 1e-6)
        assertEquals(15.3647, pos1.latitude, 1e-6)
        assertEquals(75.1240, pos1.component1(), 1e-6)
        assertEquals(15.3647, pos1.component2(), 1e-6)

        val pos2 = lineString.coordinates[1]
        assertEquals(75.2000, pos2.longitude, 1e-6)
        assertEquals(15.4000, pos2.latitude, 1e-6)
    }

    @Test
    fun testMultipleRoutePointsPreserveOriginalOrder() {
        val points = listOf(
            RoutePoint(latitude = 15.10, longitude = 75.10),
            RoutePoint(latitude = 15.20, longitude = 75.20),
            RoutePoint(latitude = 15.30, longitude = 75.30),
            RoutePoint(latitude = 15.40, longitude = 75.40)
        )
        val lineString = RouteMapAdapter.toLineString(points)

        assertNotNull(lineString)
        assertEquals(4, lineString!!.coordinates.size)
        for (i in points.indices) {
            assertEquals(points[i].longitude, lineString.coordinates[i].longitude, 1e-6)
            assertEquals(points[i].latitude, lineString.coordinates[i].latitude, 1e-6)
        }
    }

    @Test
    fun testEmptyWaypointListDoesNotProduceRoute() {
        assertNull("Empty list must return null", RouteMapAdapter.toLineString(emptyList()))
        assertNull("Null list must return null", RouteMapAdapter.toLineString(null))
    }

    @Test
    fun testOneWaypointDoesNotProduceInvalidLineString() {
        val singlePoint = listOf(RoutePoint(latitude = 15.3647, longitude = 75.1240))
        assertNull("Single waypoint cannot form a LineString and must return null", RouteMapAdapter.toLineString(singlePoint))
    }

    @Test
    fun testInvalidNonFiniteCoordinatesAreHandledSafely() {
        // List with only non-finite coordinates
        val invalidOnly = listOf(
            RoutePoint(latitude = Double.NaN, longitude = 75.1240),
            RoutePoint(latitude = 15.3647, longitude = Double.POSITIVE_INFINITY)
        )
        assertNull("All non-finite points filtered out, resulting in <2 points, returning null", RouteMapAdapter.toLineString(invalidOnly))

        // List where non-finite coordinates are filtered, leaving less than 2 valid points
        val oneValidOneInvalid = listOf(
            RoutePoint(latitude = 15.3647, longitude = 75.1240),
            RoutePoint(latitude = Double.NaN, longitude = Double.NaN)
        )
        assertNull("One valid point remaining should return null", RouteMapAdapter.toLineString(oneValidOneInvalid))

        // List where non-finite coordinate in the middle is filtered safely, keeping 2 valid points
        val withInvalidInMiddle = listOf(
            RoutePoint(latitude = 15.3647, longitude = 75.1240),
            RoutePoint(latitude = Double.NaN, longitude = 75.1500),
            RoutePoint(latitude = 15.4000, longitude = 75.2000)
        )
        val lineString = RouteMapAdapter.toLineString(withInvalidInMiddle)
        assertNotNull(lineString)
        assertEquals(2, lineString!!.coordinates.size)
        assertEquals(75.1240, lineString.coordinates[0].longitude, 1e-6)
        assertEquals(75.2000, lineString.coordinates[1].longitude, 1e-6)
    }
}
