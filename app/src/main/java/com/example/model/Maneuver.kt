package com.example.model

enum class ManeuverType {
    DEPART,
    STRAIGHT,
    TURN_SLIGHT_LEFT,
    TURN_LEFT,
    TURN_SHARP_LEFT,
    TURN_SLIGHT_RIGHT,
    TURN_RIGHT,
    TURN_SHARP_RIGHT,
    U_TURN,
    ROUNDABOUT,
    FORK_LEFT,
    FORK_RIGHT,
    MERGE,
    ARRIVE
}

/**
 * Step-by-step turn guidance maneuver for the rider.
 */
data class Maneuver(
    val id: String,
    val type: ManeuverType,
    val instruction: String,
    val roadName: String,
    val distanceMeters: Int,
    val point: RoutePoint
)
