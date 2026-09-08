package com.example.data

import com.example.model.Maneuver
import com.example.model.ManeuverType
import com.example.model.Route
import com.example.model.RoutePoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface RouteRepository {
    val availableRoutes: List<Route>
    val selectedRoute: StateFlow<Route>
    fun selectRoute(routeId: String)
    fun searchDestinations(query: String): List<Route>
}

class SampleRouteRepository : RouteRepository {

    private val sampleRoutesList = listOf(
        Route(
            id = "route_bear_peak",
            title = "Bear Peak Mountain Pass",
            summary = "Scenic high-elevation twisties with 24 sweeping turns and panoramic ridge views",
            startLocation = RoutePoint(37.7749, -122.4194, 25.0, "St. Edwards Way (Rider Base)"),
            destination = RoutePoint(37.8920, -122.5650, 680.0, "Bear Peak Lookout Summit"),
            totalDistanceMeters = 42800, // 42.8 km / ~26.6 mi
            estimatedDurationSeconds = 3120, // ~52 min
            scenicRating = 5,
            hasTwistySegments = true,
            waypoints = listOf(
                RoutePoint(37.7749, -122.4194, 25.0, "Start"),
                RoutePoint(37.7950, -122.4400, 75.0, "Skyline Blvd Entrance"),
                RoutePoint(37.8200, -122.4800, 210.0, "Pine Ridge S-Curves"),
                RoutePoint(37.8500, -122.5100, 440.0, "Switchback Junction"),
                RoutePoint(37.8750, -122.5400, 590.0, "Highland Vista Point"),
                RoutePoint(37.8920, -122.5650, 680.0, "Summit Destination")
            ),
            maneuvers = listOf(
                Maneuver("m1", ManeuverType.DEPART, "Head north on St. Edwards Way", "St. Edwards Way", 850, RoutePoint(37.7749, -122.4194)),
                Maneuver("m2", ManeuverType.TURN_RIGHT, "Turn right onto Skyline Ridge Blvd", "Skyline Ridge Blvd", 4200, RoutePoint(37.7950, -122.4400)),
                Maneuver("m3", ManeuverType.TURN_SLIGHT_LEFT, "Bear left through canyon curves", "Canyon Pass Rd", 7800, RoutePoint(37.8200, -122.4800)),
                Maneuver("m4", ManeuverType.TURN_SHARP_RIGHT, "Sharp right onto Bear Peak Ascent", "Bear Peak Ascent Way", 6100, RoutePoint(37.8500, -122.5100)),
                Maneuver("m5", ManeuverType.ROUNDABOUT, "Take 2nd exit toward summit", "Summit Loop", 1400, RoutePoint(37.8750, -122.5400)),
                Maneuver("m6", ManeuverType.ARRIVE, "Arrive at Bear Peak Lookout on right", "Lookout Parking", 0, RoutePoint(37.8920, -122.5650))
            )
        ),
        Route(
            id = "route_coastal_highway",
            title = "Pacific Coastal Highway 1",
            summary = "Ocean cliffside carving with steady sea breeze and fast sweeping curves",
            startLocation = RoutePoint(37.7749, -122.4194, 25.0, "St. Edwards Way (Rider Base)"),
            destination = RoutePoint(37.6200, -122.4900, 45.0, "Pacific Palisades Cove"),
            totalDistanceMeters = 68400, // 68.4 km / ~42.5 mi
            estimatedDurationSeconds = 4800, // ~1h 20m
            scenicRating = 5,
            hasTwistySegments = true,
            waypoints = listOf(
                RoutePoint(37.7749, -122.4194, 25.0, "Start"),
                RoutePoint(37.7400, -122.4500, 30.0, "Great Highway"),
                RoutePoint(37.7000, -122.4800, 50.0, "Mori Point Curves"),
                RoutePoint(37.6500, -122.5100, 95.0, "Devil's Slide Bypass"),
                RoutePoint(37.6200, -122.4900, 45.0, "Palisades Cove")
            ),
            maneuvers = listOf(
                Maneuver("c1", ManeuverType.DEPART, "Depart south from base", "St. Edwards Way", 1200, RoutePoint(37.7749, -122.4194)),
                Maneuver("c2", ManeuverType.TURN_LEFT, "Turn left onto Coastal Highway 1", "Highway 1 South", 18500, RoutePoint(37.7400, -122.4500)),
                Maneuver("c3", ManeuverType.STRAIGHT, "Continue straight along cliffside road", "Highway 1", 24000, RoutePoint(37.7000, -122.4800)),
                Maneuver("c4", ManeuverType.TURN_RIGHT, "Turn right into coastal overlook", "Cove Access Rd", 650, RoutePoint(37.6200, -122.4900)),
                Maneuver("c5", ManeuverType.ARRIVE, "Arrive at Palisades Cove", "Cove Parking", 0, RoutePoint(37.6200, -122.4900))
            )
        ),
        Route(
            id = "route_redwood_run",
            title = "Redwood Valley Circuit",
            summary = "Deep forest shaded twisties with switchbacks through old-growth giants",
            startLocation = RoutePoint(37.7749, -122.4194, 25.0, "St. Edwards Way (Rider Base)"),
            destination = RoutePoint(37.8100, -122.2500, 310.0, "Redwood Gate Clearing"),
            totalDistanceMeters = 31500, // 31.5 km / ~19.5 mi
            estimatedDurationSeconds = 2280, // ~38 min
            scenicRating = 4,
            hasTwistySegments = true,
            waypoints = listOf(
                RoutePoint(37.7749, -122.4194, 25.0, "Start"),
                RoutePoint(37.7900, -122.3500, 60.0, "Valley Way"),
                RoutePoint(37.8000, -122.3000, 180.0, "Fern Canyon Pass"),
                RoutePoint(37.8100, -122.2500, 310.0, "Redwood Gate")
            ),
            maneuvers = listOf(
                Maneuver("r1", ManeuverType.DEPART, "Ride east on St. Edwards Way", "St. Edwards Way", 600, RoutePoint(37.7749, -122.4194)),
                Maneuver("r2", ManeuverType.TURN_RIGHT, "Turn right onto Valley Way", "Valley Way", 7200, RoutePoint(37.7900, -122.3500)),
                Maneuver("r3", ManeuverType.TURN_SHARP_LEFT, "Sharp left onto Old Redwood Creek Rd", "Old Redwood Creek Rd", 11400, RoutePoint(37.8000, -122.3000)),
                Maneuver("r4", ManeuverType.ARRIVE, "Arrive at Redwood Gate Clearing", "Trailhead", 0, RoutePoint(37.8100, -122.2500))
            )
        ),
        Route(
            id = "route_moto_club",
            title = "Moto Cafe & Club Depot",
            summary = "Quick city-to-suburb connector route to the rider community meeting spot",
            startLocation = RoutePoint(37.7749, -122.4194, 25.0, "St. Edwards Way (Rider Base)"),
            destination = RoutePoint(37.7550, -122.4050, 30.0, "Moto Haus Cafe & Workshop"),
            totalDistanceMeters = 14200, // 14.2 km / ~8.8 mi
            estimatedDurationSeconds = 1320, // ~22 min
            scenicRating = 3,
            hasTwistySegments = false,
            waypoints = listOf(
                RoutePoint(37.7749, -122.4194, 25.0, "Start"),
                RoutePoint(37.7650, -122.4120, 28.0, "Mission Arterial"),
                RoutePoint(37.7550, -122.4050, 30.0, "Moto Haus Cafe")
            ),
            maneuvers = listOf(
                Maneuver("m_c1", ManeuverType.DEPART, "Start on St. Edwards Way", "St. Edwards Way", 500, RoutePoint(37.7749, -122.4194)),
                Maneuver("m_c2", ManeuverType.TURN_LEFT, "Turn left onto Grand Ave", "Grand Ave", 3200, RoutePoint(37.7650, -122.4120)),
                Maneuver("m_c3", ManeuverType.ARRIVE, "Arrive at Moto Haus Cafe on left", "Parking Bay", 0, RoutePoint(37.7550, -122.4050))
            )
        )
    )

    override val availableRoutes: List<Route> = sampleRoutesList

    private val _selectedRoute = MutableStateFlow(sampleRoutesList.first())
    override val selectedRoute: StateFlow<Route> = _selectedRoute.asStateFlow()

    override fun selectRoute(routeId: String) {
        val found = sampleRoutesList.find { it.id == routeId }
        if (found != null) {
            _selectedRoute.value = found
        }
    }

    override fun searchDestinations(query: String): List<Route> {
        if (query.isBlank()) return sampleRoutesList
        val cleanQuery = query.trim().lowercase()
        return sampleRoutesList.filter {
            it.title.lowercase().contains(cleanQuery) ||
            it.destination.name?.lowercase()?.contains(cleanQuery) == true ||
            it.summary.lowercase().contains(cleanQuery)
        }
    }

    companion object {
        val instance: SampleRouteRepository by lazy { SampleRouteRepository() }
    }
}
