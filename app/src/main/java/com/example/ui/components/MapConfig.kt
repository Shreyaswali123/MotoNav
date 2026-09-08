package com.example.ui.components

/**
 * Configuration constants for the MapLibre map integration in MotoNav.
 * The style provider and default geographic parameters are isolated here
 * so that they can easily be reconfigured or replaced later.
 */
object MapConfig {
    /**
     * MapLibre style URI.
     * OpenFreeMap Liberty is a publicly accessible, free OSM-derived vector tile style
     * that requires no API keys, tokens, or proprietary credentials.
     */
    const val DEFAULT_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

    // Sensible initial camera coordinates for Hubballi, Karnataka
    const val DEFAULT_LATITUDE = 15.3647
    const val DEFAULT_LONGITUDE = 75.1240
    const val DEFAULT_ZOOM = 12.0
}
