package com.example.ui.components

import com.example.model.RoutePoint
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Position

/**
 * Adapter converting MotoNav route domain models into MapLibre GeoJSON primitives.
 */
object RouteMapAdapter {

    /**
     * Converts a list of [RoutePoint] waypoints into a GeoJSON [LineString].
     *
     * Rules per Task 2 requirements:
     * 1. If waypoints is null, empty, or has fewer than 2 valid points, returns null.
     * 2. Non-finite latitude or longitude values (NaN, Infinite) are safely ignored.
     * 3. Coordinates are ordered strictly as (longitude, latitude) in GeoJSON specification.
     * 4. Original sequence order of waypoints is preserved.
     */
    fun toLineString(waypoints: List<RoutePoint>?): LineString? {
        if (waypoints.isNullOrEmpty()) return null

        val validPositions = waypoints
            .filter { it.latitude.isFinite() && it.longitude.isFinite() }
            .map { Position(longitude = it.longitude, latitude = it.latitude) }

        if (validPositions.size < 2) return null

        return LineString(validPositions)
    }
}
