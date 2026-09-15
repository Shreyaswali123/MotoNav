package com.example.benchmark

import org.json.JSONObject

/**
 * Minimal internal response model to parse Photon results.
 * Extracts at minimum:
 * - display / name
 * - latitude
 * - longitude
 * - city / locality
 * - state
 * - country
 * - result type / category
 * - provider identifier ("photon")
 */
data class PhotonLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val resultType: String? = null,
    val category: String? = null,
    val provider: String = "photon",
    val rawAddress: String = ""
) {
    val displayName: String
        get() = when {
            rawAddress.isNotBlank() -> rawAddress
            name.isNotBlank() -> {
                listOfNotNull(name, city, state, country)
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
            }
            else -> "Location ($latitude, $longitude)"
        }

    fun isValid(): Boolean {
        return latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0
    }
}

object PhotonParser {
    fun parse(jsonString: String): List<PhotonLocation> {
        if (jsonString.isBlank()) return emptyList()

        val root = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            return emptyList()
        }

        val features = root.optJSONArray("features") ?: return emptyList()
        val results = mutableListOf<PhotonLocation>()

        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coordinates = geometry.optJSONArray("coordinates") ?: continue

            // In GeoJSON, coordinates are [longitude, latitude]
            val lon = coordinates.optDouble(0, Double.NaN)
            val lat = coordinates.optDouble(1, Double.NaN)

            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                continue
            }

            val props = feature.optJSONObject("properties") ?: JSONObject()
            val name = props.optString("name", "").trim()
            val city = props.optString("city", "").trim().ifEmpty {
                props.optString("locality", "").trim().ifEmpty { null }
            }
            val state = props.optString("state", "").trim().ifEmpty { null }
            val country = props.optString("country", "").trim().ifEmpty { null }
            val type = props.optString("type", "").trim().ifEmpty {
                props.optString("osm_value", "").trim().ifEmpty { null }
            }
            val category = props.optString("osm_key", "").trim().ifEmpty { null }

            val addressParts = listOfNotNull(
                name.ifEmpty { null },
                props.optString("street", "").trim().ifEmpty { null },
                props.optString("district", "").trim().ifEmpty { null },
                city,
                state,
                country
            ).filter { it.isNotBlank() }

            val rawAddress = if (addressParts.isNotEmpty()) {
                addressParts.joinToString(", ")
            } else {
                name
            }

            results.add(
                PhotonLocation(
                    name = if (name.isNotBlank()) name else "Location ($lat, $lon)",
                    latitude = lat,
                    longitude = lon,
                    city = city,
                    state = state,
                    country = country,
                    resultType = type,
                    category = category,
                    provider = "photon",
                    rawAddress = rawAddress
                )
            )
        }

        return results
    }
}
