package com.example.model

/**
 * High-level motorcycle navigation route model.
 * Isolated from BLE and ESP32 binary protocols.
 */
data class Route(
    val id: String,
    val title: String,
    val summary: String,
    val startLocation: RoutePoint,
    val destination: RoutePoint,
    val totalDistanceMeters: Int,
    val estimatedDurationSeconds: Int,
    val waypoints: List<RoutePoint> = emptyList(),
    val maneuvers: List<Maneuver> = emptyList(),
    val scenicRating: Int = 5,
    val hasTwistySegments: Boolean = true
) {
    val totalDistanceKm: Double get() = totalDistanceMeters / 1000.0
    val totalDistanceMiles: Double get() = totalDistanceMeters * 0.000621371
    val estimatedDurationMinutes: Int get() = estimatedDurationSeconds / 60
}
