package com.example.model

/**
 * Geographic coordinate point along a motorcycle navigation route.
 */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double = 0.0,
    val name: String? = null
)
