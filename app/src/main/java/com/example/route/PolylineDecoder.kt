package com.example.route

import com.example.model.RoutePoint

/**
 * Decodes Valhalla's encoded polyline strings.
 * Valhalla coordinates are encoded with 6 decimal places of precision (1e6 factor).
 */
object PolylineDecoder {

    private const val MIN_VALID_CHAR_CODE = 63
    private const val MAX_VALID_CHAR_CODE = 126

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
            val (dlat, latIndex) = decodeVarint(encoded, index, len, "latitude")
            index = latIndex

            if (index >= len) {
                throw IllegalArgumentException(
                    "Malformed polyline: incomplete coordinate pair, missing longitude at index $index"
                )
            }

            val (dlng, lngIndex) = decodeVarint(encoded, index, len, "longitude")
            index = lngIndex

            lat = try {
                Math.addExact(lat, dlat)
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("Malformed polyline: coordinate delta overflow in latitude", e)
            }

            lng = try {
                Math.addExact(lng, dlng)
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("Malformed polyline: coordinate delta overflow in longitude", e)
            }

            val latitude = lat / 1e6
            val longitude = lng / 1e6

            if (latitude !in -90.0..90.0) {
                throw IllegalArgumentException("Decoded latitude out of range: $latitude")
            }
            if (longitude !in -180.0..180.0) {
                throw IllegalArgumentException("Decoded longitude out of range: $longitude")
            }

            poly.add(RoutePoint(latitude = latitude, longitude = longitude))
        }

        return poly
    }

    private fun decodeVarint(
        encoded: String,
        startIndex: Int,
        len: Int,
        fieldName: String
    ): Pair<Int, Int> {
        var index = startIndex
        var shift = 0
        var result = 0
        var b: Int

        do {
            if (index >= len) {
                throw IllegalArgumentException(
                    "Malformed polyline: truncated varint for $fieldName at index $index"
                )
            }

            val c = encoded[index]
            val code = c.code
            if (code !in MIN_VALID_CHAR_CODE..MAX_VALID_CHAR_CODE) {
                throw IllegalArgumentException(
                    "Malformed polyline: invalid character '$c' (code $code) at index $index"
                )
            }
            index++

            if (shift > 30) {
                throw IllegalArgumentException(
                    "Malformed polyline: varint exceeds 32 bits for $fieldName at index $index"
                )
            }

            b = code - 63
            if (shift == 30 && (b and 0x1c) != 0) {
                throw IllegalArgumentException(
                    "Malformed polyline: varint exceeds 32 bits for $fieldName at index $index"
                )
            }

            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)

        val delta = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
        return Pair(delta, index)
    }
}
