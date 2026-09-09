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

    @Test
    fun testOriginMarkerExtraction() {
        val validPoint = RoutePoint(latitude = 15.3647, longitude = 75.1240, name = "Hubballi Station")
        val marker = RouteMapAdapter.extractOriginMarker(validPoint)
        assertNotNull(marker)
        assertEquals("origin_marker", marker!!.id)
        assertEquals(MapMarkerType.ORIGIN, marker.type)
        assertEquals(75.1240, marker.position.longitude, 1e-6)
        assertEquals(15.3647, marker.position.latitude, 1e-6)

        assertNull(RouteMapAdapter.extractOriginMarker(null))
        assertNull(RouteMapAdapter.extractOriginMarker(RoutePoint(latitude = Double.NaN, longitude = 75.1240)))
    }

    @Test
    fun testDestinationMarkerExtraction() {
        val validPoint = RoutePoint(latitude = 15.4500, longitude = 75.2000, name = "Dharwad Center")
        val marker = RouteMapAdapter.extractDestinationMarker(validPoint)
        assertNotNull(marker)
        assertEquals("destination_marker", marker!!.id)
        assertEquals(MapMarkerType.DESTINATION, marker.type)
        assertEquals(75.2000, marker.position.longitude, 1e-6)
        assertEquals(15.4500, marker.position.latitude, 1e-6)

        assertNull(RouteMapAdapter.extractDestinationMarker(null))
        assertNull(RouteMapAdapter.extractDestinationMarker(RoutePoint(latitude = 15.4500, longitude = Double.POSITIVE_INFINITY)))
    }

    @Test
    fun testManeuverMarkersExtractionAndTypes() {
        val maneuvers = listOf(
            com.example.model.Maneuver("m1", com.example.model.ManeuverType.DEPART, "Start", "", 100, RoutePoint(15.10, 75.10)),
            com.example.model.Maneuver("m2", com.example.model.ManeuverType.TURN_LEFT, "Turn Left", "Main St", 500, RoutePoint(15.20, 75.20)),
            com.example.model.Maneuver("m3", com.example.model.ManeuverType.TURN_RIGHT, "Turn Right", "Cross St", 800, RoutePoint(15.30, 75.30)),
            com.example.model.Maneuver("m4", com.example.model.ManeuverType.STRAIGHT, "Go straight", "Hwy", 1200, RoutePoint(15.40, 75.40)),
            com.example.model.Maneuver("m5", com.example.model.ManeuverType.ARRIVE, "Arrive", "Destination", 0, RoutePoint(15.50, 75.50))
        )

        val markers = RouteMapAdapter.extractManeuverMarkers(maneuvers)
        // DEPART and STRAIGHT should be filtered out. LEFT, RIGHT, and ARRIVE should be included.
        assertEquals(3, markers.size)
        assertEquals(MapMarkerType.MANEUVER_LEFT, markers[0].type)
        assertEquals("m2", markers[0].id)
        assertEquals(MapMarkerType.MANEUVER_RIGHT, markers[1].type)
        assertEquals("m3", markers[1].id)
        assertEquals(MapMarkerType.MANEUVER_ARRIVE, markers[2].type)
        assertEquals("m5", markers[2].id)
    }

    @Test
    fun testManeuverMarkerClutterAvoidanceNearEndpointsAndDuplicates() {
        val origin = RoutePoint(15.1000, 75.1000)
        val destination = RoutePoint(15.5000, 75.5000)

        val maneuvers = listOf(
            // Maneuver right at origin -> should be skipped
            com.example.model.Maneuver("m_start", com.example.model.ManeuverType.TURN_LEFT, "Turn at start", "", 50, RoutePoint(15.10005, 75.10005)),
            // Valid maneuver in middle
            com.example.model.Maneuver("m_mid1", com.example.model.ManeuverType.TURN_RIGHT, "Turn right", "", 200, RoutePoint(15.3000, 75.3000)),
            // Duplicate maneuver right next to m_mid1 (<0.0003 deg away) -> should be skipped
            com.example.model.Maneuver("m_mid2", com.example.model.ManeuverType.TURN_RIGHT, "Turn right again", "", 20, RoutePoint(15.3001, 75.3001)),
            // Maneuver right at destination -> should be skipped
            com.example.model.Maneuver("m_end", com.example.model.ManeuverType.ARRIVE, "Arrive at destination", "", 0, RoutePoint(15.50002, 75.50002))
        )

        val markers = RouteMapAdapter.extractManeuverMarkers(maneuvers, origin = origin, destination = destination)
        // Only m_mid1 should survive clutter filtering
        assertEquals(1, markers.size)
        assertEquals("m_mid1", markers[0].id)
    }

    @Test
    fun testComputeRouteBoundingBoxEncompassesAllPoints() {
        val origin = RoutePoint(latitude = 15.10, longitude = 75.10)
        val dest = RoutePoint(latitude = 15.30, longitude = 75.30)
        // Waypoints curve far out to (15.50, 75.70)
        val waypoints = listOf(
            RoutePoint(latitude = 15.10, longitude = 75.10),
            RoutePoint(latitude = 15.50, longitude = 75.70),
            RoutePoint(latitude = 15.30, longitude = 75.30)
        )

        val bbox = RouteMapAdapter.computeRouteBoundingBox(origin, dest, waypoints)
        assertNotNull(bbox)
        // South/West should be 15.10, 75.10
        assertEquals(75.10, bbox!!.west, 1e-5)
        assertEquals(15.10, bbox.south, 1e-5)
        // North/East must include the curving waypoint at 15.50, 75.70, not just the destination
        assertEquals(75.70, bbox.east, 1e-5)
        assertEquals(15.50, bbox.north, 1e-5)
    }

    @Test
    fun testComputeRouteBoundingBoxWithZeroSpanPadsSafely() {
        val singlePoint = RoutePoint(latitude = 15.3647, longitude = 75.1240)
        val bbox = RouteMapAdapter.computeRouteBoundingBox(singlePoint, singlePoint, listOf(singlePoint))
        assertNotNull("Bounding box for single point should pad safely and return non-null", bbox)
        assert(bbox!!.east > bbox.west)
        assert(bbox.north > bbox.south)
    }

    @Test
    fun testComputeRouteSignatureChangesWhenRouteGeometryChanges() {
        val origin = RoutePoint(latitude = 15.10, longitude = 75.10)
        val dest = RoutePoint(latitude = 15.30, longitude = 75.30)
        val wp1 = listOf(origin, dest)
        val wp2 = listOf(origin, RoutePoint(15.20, 75.20), dest)

        val sig1 = RouteMapAdapter.computeRouteSignature("route_1", origin, dest, wp1)
        val sig1Again = RouteMapAdapter.computeRouteSignature("route_1", origin, dest, wp1)
        val sig2 = RouteMapAdapter.computeRouteSignature("route_1", origin, dest, wp2)
        val sigDifferentId = RouteMapAdapter.computeRouteSignature("route_2", origin, dest, wp1)

        assertEquals("Same geometry produces identical signature", sig1, sig1Again)
        assert(sig1 != sig2) { "Different waypoints must produce different signature" }
        assert(sig1 != sigDifferentId) { "Different route id must produce different signature" }
    }
}
