package com.example.route

import com.example.model.RoutePoint

/**
 * Decodes Valhalla's encoded polyline strings.
 * Valhalla coordinates are encoded with 6 decimal places of precision (1e6 factor).
 */
object PolylineDecoder {

    /**
     * Decodes an encoded polyline string with precision factor 1e6 into a list of [RoutePoint].
     */
    fun decodePolyline6(encoded: String): List<RoutePoint> {
        if (encoded.isBlank()) return emptyList()

        val poly = ArrayList<RoutePoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                if (index >= len) break
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                if (index >= len) break
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            val latitude = lat / 1e6
            val longitude = lng / 1e6
            poly.add(RoutePoint(latitude = latitude, longitude = longitude))
        }

        return poly
    }
}
