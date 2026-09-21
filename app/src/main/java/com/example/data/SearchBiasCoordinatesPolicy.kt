package com.example.data

import kotlin.math.abs

/**
 * Architectural policy defining coordinate validation and regression invariants
 * for search biasing.
 *
 * Invariant: Search bias MUST use the rider's physical phone location.
 * Search bias MUST NEVER be derived from route start location, selected route origin,
 * or default sample route coordinates (San Francisco 37.7749, -122.4194).
 */
object SearchBiasCoordinatesPolicy {
    const val SAMPLE_LATITUDE = 37.7749
    const val SAMPLE_LONGITUDE = -122.4194

    /**
     * Returns true if coordinates are null, non-finite, out of geographic bounds,
     * or match the known sample route origin coordinates within epsilon.
     */
    fun isSampleOrInvalid(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return true
        if (!lat.isFinite() || !lon.isFinite()) return true
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return true
        // Regression defense: Never allow sample route origin coordinates to bias search
        if (abs(lat - SAMPLE_LATITUDE) < 0.001 && abs(lon - SAMPLE_LONGITUDE) < 0.001) {
            return true
        }
        return false
    }
}
