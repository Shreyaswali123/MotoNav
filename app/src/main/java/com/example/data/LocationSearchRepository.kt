package com.example.data

import com.example.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Data model for a searchable geographic place.
 */
data class SearchLocation(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double = 0.0
) {
    fun toRoutePoint(): RoutePoint = RoutePoint(
        latitude = latitude,
        longitude = longitude,
        elevationMeters = elevationMeters,
        name = name
    )
}

/**
 * Repository interface for searching locations without proprietary/paid APIs.
 */
interface LocationSearchRepository {
    suspend fun searchLocations(query: String): List<SearchLocation>
    fun getPopularLocations(): List<SearchLocation>
}

/**
 * Default implementation combining fast local curated places (Hubballi landmarks,
 * preset ride hubs) with live OpenStreetMap Nominatim geocoding fallback.
 */
class DefaultLocationSearchRepository(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) : LocationSearchRepository {

    private val curatedLocations = listOf(
        SearchLocation(
            id = "loc_kle_tech",
            name = "KLE Technological University, Hubballi",
            address = "Vidyanagar, Pune-Bangalore Rd, Hubballi, Karnataka 580031",
            latitude = 15.3690067,
            longitude = 75.1236571,
            elevationMeters = 640.0
        ),
        SearchLocation(
            id = "loc_tolankere",
            name = "TolanKere, Hubballi",
            address = "Tolankere Lake & Garden, Vivekanand Nagar, Hubballi, Karnataka 580030",
            latitude = 15.3582101,
            longitude = 75.1032344,
            elevationMeters = 635.0
        ),
        SearchLocation(
            id = "loc_unkal_lake",
            name = "Unkal Lake, Hubballi",
            address = "Unkal Lake Promenade, Gokul Rd, Hubballi, Karnataka 580031",
            latitude = 15.3785,
            longitude = 75.1168,
            elevationMeters = 645.0
        ),
        SearchLocation(
            id = "loc_hubballi_station",
            name = "Hubballi Railway Station",
            address = "SMR Railway Junction, Station Rd, Hubballi, Karnataka 580020",
            latitude = 15.3486,
            longitude = 75.1481,
            elevationMeters = 630.0
        ),
        SearchLocation(
            id = "loc_gokul_road",
            name = "Gokul Road, Hubballi",
            address = "Gokul Industrial & Commercial Area, Hubballi, Karnataka 580030",
            latitude = 15.3556,
            longitude = 75.0991,
            elevationMeters = 632.0
        ),
        SearchLocation(
            id = "loc_vidyanagar",
            name = "Vidyanagar, Hubballi",
            address = "Vidyanagar Main Hub, Hubballi, Karnataka 580021",
            latitude = 15.3647,
            longitude = 75.1240,
            elevationMeters = 638.0
        ),
        SearchLocation(
            id = "loc_dharwad_bus_stand",
            name = "Dharwad Old Bus Stand",
            address = "Line Bazaar, Dharwad, Karnataka 580001",
            latitude = 15.4589,
            longitude = 75.0078,
            elevationMeters = 730.0
        ),
        SearchLocation(
            id = "loc_iit_dharwad",
            name = "IIT Dharwad",
            address = "Permanent Campus, Chikkamalligawad, Dharwad, Karnataka 580007",
            latitude = 15.4889,
            longitude = 74.9339,
            elevationMeters = 710.0
        ),
        SearchLocation(
            id = "loc_st_edwards",
            name = "St. Edwards Way (Rider Base)",
            address = "Rider Base Station, Bay Area",
            latitude = 37.7749,
            longitude = -122.4194,
            elevationMeters = 25.0
        ),
        SearchLocation(
            id = "loc_bear_peak",
            name = "Bear Peak Lookout Summit",
            address = "Mountain Pass Scenic Overlook",
            latitude = 37.8920,
            longitude = -122.5650,
            elevationMeters = 680.0
        ),
        SearchLocation(
            id = "loc_pacific_palisades",
            name = "Pacific Palisades Cove",
            address = "Coastal Highway 1 Turnoff",
            latitude = 37.6200,
            longitude = -122.4900,
            elevationMeters = 45.0
        ),
        SearchLocation(
            id = "loc_redwood_gate",
            name = "Redwood Gate Clearing",
            address = "Redwood Valley Circuit",
            latitude = 37.8100,
            longitude = -122.2500,
            elevationMeters = 310.0
        ),
        SearchLocation(
            id = "loc_moto_haus",
            name = "Moto Haus Cafe & Workshop",
            address = "Community Rider Depot",
            latitude = 37.7550,
            longitude = -122.4050,
            elevationMeters = 30.0
        )
    )

    override fun getPopularLocations(): List<SearchLocation> = curatedLocations

    override suspend fun searchLocations(query: String): List<SearchLocation> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) {
            return@withContext curatedLocations
        }

        // 1. Check local curated index first (instant and 100% reliable)
        val localMatches = curatedLocations.filter { loc ->
            val nameClean = loc.name.lowercase()
            val addrClean = loc.address.lowercase()
            nameClean.contains(cleanQuery) ||
                addrClean.contains(cleanQuery) ||
                (cleanQuery.contains("kle") && nameClean.contains("kle")) ||
                ((cleanQuery.contains("tolan") || cleanQuery.contains("kere")) && nameClean.contains("tolan"))
        }.toMutableList()

        // 2. Query OpenStreetMap Nominatim for general queries
        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=5&addressdetails=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MotoNav-Android-Companion/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyString = response.body?.string()
                if (!bodyString.isNullOrBlank()) {
                    val jsonArray = JSONArray(bodyString)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val displayName = obj.optString("display_name", "")
                        val lat = obj.optDouble("lat", 0.0)
                        val lon = obj.optDouble("lon", 0.0)

                        if (lat != 0.0 && lon != 0.0 && displayName.isNotBlank()) {
                            // Extract a clean primary title
                            val parts = displayName.split(",")
                            val title = if (parts.isNotEmpty()) parts[0].trim() else displayName
                            val subtitle = if (parts.size > 1) parts.drop(1).joinToString(",").trim() else ""

                            // Avoid duplicates
                            val isDuplicate = localMatches.any {
                                Math.abs(it.latitude - lat) < 0.001 && Math.abs(it.longitude - lon) < 0.001
                            }

                            if (!isDuplicate) {
                                localMatches.add(
                                    SearchLocation(
                                        id = "osm_${obj.optString("place_id", i.toString())}",
                                        name = title,
                                        address = subtitle.ifBlank { displayName },
                                        latitude = lat,
                                        longitude = lon
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Fall back gracefully to local curated matches if network fails
        }

        localMatches
    }

    companion object {
        val instance: DefaultLocationSearchRepository by lazy { DefaultLocationSearchRepository() }
    }
}
