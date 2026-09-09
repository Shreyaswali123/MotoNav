package com.example.ui.components

import com.example.model.Maneuver
import com.example.model.ManeuverType
import com.example.model.Route
import com.example.model.RoutePoint
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Position

enum class MapMarkerType {
    ORIGIN,
    DESTINATION,
    MANEUVER_LEFT,
    MANEUVER_RIGHT,
    MANEUVER_ARRIVE
}

data class MapMarker(
    val id: String,
    val type: MapMarkerType,
    val position: Position,
    val label: String? = null
)

/**
 * Adapter converting MotoNav route domain models into MapLibre GeoJSON primitives,
 * camera bounds, and map marker presentations.
 */
object RouteMapAdapter {

    /**
     * Checks if given latitude and longitude coordinates are finite and within valid geographic bounds:
     * Latitude in [-90, 90], Longitude in [-180, 180].
     */
    fun isValidCoordinate(latitude: Double, longitude: Double): Boolean {
        return latitude.isFinite() &&
                longitude.isFinite() &&
                latitude >= -90.0 && latitude <= 90.0 &&
                longitude >= -180.0 && longitude <= 180.0
    }

    /**
     * Checks if [point] is non-null and contains valid geographic coordinates.
     */
    fun isValid(point: RoutePoint?): Boolean {
        if (point == null) return false
        return isValidCoordinate(point.latitude, point.longitude)
    }

    /**
     * Converts a list of [RoutePoint] waypoints into a GeoJSON [LineString].
     *
     * Rules:
     * 1. If waypoints is null, empty, or has fewer than 2 valid points, returns null.
     * 2. Non-finite or out-of-range coordinates are safely ignored.
     * 3. Coordinates are ordered strictly as (longitude, latitude) in GeoJSON specification.
     * 4. Original sequence order of waypoints is preserved.
     */
    fun toLineString(waypoints: List<RoutePoint>?): LineString? {
        if (waypoints.isNullOrEmpty()) return null

        val validPositions = waypoints
            .filter { isValid(it) }
            .map { Position(longitude = it.longitude, latitude = it.latitude) }

        if (validPositions.size < 2) return null

        return LineString(validPositions)
    }

    /**
     * Computes the bounding box encompassing the route's start location, destination,
     * and all valid waypoints.
     *
     * Returns null if there are no valid coordinates or if the input is null/empty.
     * Pads zero-span bounds to avoid singular bounding boxes.
     */
    fun computeRouteBoundingBox(
        startLocation: RoutePoint?,
        destination: RoutePoint?,
        waypoints: List<RoutePoint>?
    ): BoundingBox? {
        val validPoints = mutableListOf<RoutePoint>()
        if (isValid(startLocation)) validPoints.add(startLocation!!)
        if (isValid(destination)) validPoints.add(destination!!)
        waypoints?.forEach {
            if (isValid(it)) validPoints.add(it)
        }

        if (validPoints.isEmpty()) return null

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        for (pt in validPoints) {
            if (pt.latitude < minLat) minLat = pt.latitude
            if (pt.latitude > maxLat) maxLat = pt.latitude
            if (pt.longitude < minLon) minLon = pt.longitude
            if (pt.longitude > maxLon) maxLon = pt.longitude
        }

        // Guard against zero-span bounds along latitude or longitude
        val minDelta = 0.005 // ~500 meters span minimum
        if (maxLat - minLat < minDelta) {
            val midLat = (minLat + maxLat) / 2.0
            minLat = midLat - minDelta / 2.0
            maxLat = midLat + minDelta / 2.0
        }
        if (maxLon - minLon < minDelta) {
            val midLon = (minLon + maxLon) / 2.0
            minLon = midLon - minDelta / 2.0
            maxLon = midLon + minDelta / 2.0
        }

        return BoundingBox(
            southwest = Position(longitude = minLon, latitude = minLat),
            northeast = Position(longitude = maxLon, latitude = maxLat)
        )
    }

    fun computeRouteBoundingBox(route: Route?): BoundingBox? {
        if (route == null) return null
        return computeRouteBoundingBox(route.startLocation, route.destination, route.waypoints)
    }

    /**
     * Generates a stable signature string representing the route's geometry identity.
     * This avoids continuous camera refitting on recomposition while triggering
     * refitting whenever a new route or modified geometry is supplied.
     */
    fun computeRouteSignature(
        routeId: String?,
        startLocation: RoutePoint?,
        destination: RoutePoint?,
        waypoints: List<RoutePoint>?
    ): String? {
        if (startLocation == null && destination == null && waypoints.isNullOrEmpty()) return null
        val firstWp = waypoints?.firstOrNull()
        val lastWp = waypoints?.lastOrNull()
        val count = waypoints?.size ?: 0
        return buildString {
            append(routeId ?: "none")
            append('|')
            append(startLocation?.latitude ?: 0.0).append(',').append(startLocation?.longitude ?: 0.0)
            append('|')
            append(destination?.latitude ?: 0.0).append(',').append(destination?.longitude ?: 0.0)
            append('|')
            append(count)
            append('|')
            append(firstWp?.latitude ?: 0.0).append(',').append(firstWp?.longitude ?: 0.0)
            append('|')
            append(lastWp?.latitude ?: 0.0).append(',').append(lastWp?.longitude ?: 0.0)
        }
    }

    fun computeRouteSignature(route: Route?): String? {
        if (route == null) return null
        return computeRouteSignature(route.id, route.startLocation, route.destination, route.waypoints)
    }

    /**
     * Extracts origin marker for map display.
     */
    fun extractOriginMarker(startLocation: RoutePoint?): MapMarker? {
        if (!isValid(startLocation)) return null
        return MapMarker(
            id = "origin_marker",
            type = MapMarkerType.ORIGIN,
            position = Position(longitude = startLocation!!.longitude, latitude = startLocation.latitude),
            label = startLocation.name ?: "Origin"
        )
    }

    /**
     * Extracts destination marker for map display.
     */
    fun extractDestinationMarker(destination: RoutePoint?): MapMarker? {
        if (!isValid(destination)) return null
        return MapMarker(
            id = "destination_marker",
            type = MapMarkerType.DESTINATION,
            position = Position(longitude = destination!!.longitude, latitude = destination.latitude),
            label = destination.name ?: "Destination"
        )
    }

    /**
     * Extracts maneuver markers for map display from the route's maneuver list.
     *
     * Rules:
     * - Only includes supported turn/arrival maneuvers: LEFT, RIGHT, and ARRIVE.
     * - Filters out non-finite or out-of-range coordinates.
     * - Avoids marker clutter: skips maneuvers within ~30m of origin or destination,
     *   and deduplicates maneuvers close to each other.
     */
    fun extractManeuverMarkers(
        maneuvers: List<Maneuver>?,
        origin: RoutePoint? = null,
        destination: RoutePoint? = null
    ): List<MapMarker> {
        if (maneuvers.isNullOrEmpty()) return emptyList()

        val markers = mutableListOf<MapMarker>()
        val threshold = 0.0003 // ~30 meters in degrees

        fun isNear(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
            val dLat = lat1 - lat2
            val dLon = lon1 - lon2
            return (dLat * dLat + dLon * dLon) < (threshold * threshold)
        }

        for (maneuver in maneuvers) {
            val pt = maneuver.point
            if (!isValid(pt)) continue

            val markerType = when (maneuver.type) {
                ManeuverType.TURN_LEFT,
                ManeuverType.TURN_SLIGHT_LEFT,
                ManeuverType.TURN_SHARP_LEFT,
                ManeuverType.FORK_LEFT -> MapMarkerType.MANEUVER_LEFT

                ManeuverType.TURN_RIGHT,
                ManeuverType.TURN_SLIGHT_RIGHT,
                ManeuverType.TURN_SHARP_RIGHT,
                ManeuverType.FORK_RIGHT -> MapMarkerType.MANEUVER_RIGHT

                ManeuverType.ARRIVE -> MapMarkerType.MANEUVER_ARRIVE

                else -> null
            } ?: continue

            // Skip if too close to origin or destination marker to prevent visual clutter
            if (origin != null && isValid(origin) && isNear(pt.latitude, pt.longitude, origin.latitude, origin.longitude)) {
                continue
            }
            if (destination != null && isValid(destination) && isNear(pt.latitude, pt.longitude, destination.latitude, destination.longitude)) {
                continue
            }

            // Deduplicate if too close to an already displayed maneuver marker
            val isDuplicate = markers.any { existing ->
                isNear(pt.latitude, pt.longitude, existing.position.latitude, existing.position.longitude)
            }
            if (isDuplicate) continue

            markers.add(
                MapMarker(
                    id = maneuver.id.ifEmpty { "maneuver_${markers.size}" },
                    type = markerType,
                    position = Position(longitude = pt.longitude, latitude = pt.latitude),
                    label = maneuver.instruction
                )
            )
        }

        return markers
    }

    fun extractManeuverMarkers(maneuvers: List<Maneuver>?): List<MapMarker> =
        extractManeuverMarkers(maneuvers, null, null)
}
