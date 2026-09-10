package com.example.data

import com.example.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
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
    val elevationMeters: Double = 0.0,
    val importance: Double = 0.0,
    val placeType: String = "",
    val category: String = ""
) {
    fun toRoutePoint(): RoutePoint = RoutePoint(
        latitude = latitude,
        longitude = longitude,
        elevationMeters = elevationMeters,
        name = name
    )

    fun isValid(): Boolean {
        return latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0
    }
}

/**
 * Result sealed hierarchy for place search operations.
 */
sealed class LocationSearchResult {
    data class Success(val locations: List<SearchLocation>) : LocationSearchResult()
    data class Empty(val query: String) : LocationSearchResult()
    data class NetworkError(
        val message: String = "Unable to search locations. Check your internet connection.",
        val fallbackLocations: List<SearchLocation> = emptyList()
    ) : LocationSearchResult()
    data class MalformedResponse(
        val message: String = "Unable to parse search results. Please try again."
    ) : LocationSearchResult()
}

/**
 * Repository interface for searching locations without proprietary/paid APIs.
 */
interface LocationSearchRepository {
    suspend fun searchLocations(query: String): List<SearchLocation>
    suspend fun search(query: String): LocationSearchResult
    suspend fun search(query: String, userLatitude: Double?, userLongitude: Double?): LocationSearchResult = search(query)
    fun getPopularLocations(): List<SearchLocation>
}

/**
 * Default implementation combining fast local curated places (Hubballi landmarks,
 * preset ride hubs) with live OpenStreetMap Nominatim geocoding fallback.
 */
class DefaultLocationSearchRepository(
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val defaultCountryCode: String? = "in"
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

    override suspend fun search(query: String): LocationSearchResult = search(query, null, null)

    override suspend fun search(
        query: String,
        userLatitude: Double?,
        userLongitude: Double?
    ): LocationSearchResult = withContext(Dispatchers.IO) {
        val normalizedQuery = PlaceQueryNormalizer.normalize(query)
        if (normalizedQuery.isBlank()) {
            return@withContext LocationSearchResult.Success(curatedLocations)
        }

        val candidates = PlaceQueryNormalizer.getCandidateQueries(query)
        val primaryQuery = candidates.firstOrNull() ?: normalizedQuery

        // 1. Primary query biased toward India (or configured defaultCountryCode)
        when (val firstResult = queryNominatim(primaryQuery, defaultCountryCode)) {
            is NominatimFetchResult.Success -> {
                val ranked = PlaceResultRanker.rankResults(
                    locations = firstResult.locations,
                    query = normalizedQuery,
                    userLatitude = userLatitude,
                    userLongitude = userLongitude
                )
                return@withContext LocationSearchResult.Success(ranked)
            }
            is NominatimFetchResult.NetworkError -> {
                return@withContext LocationSearchResult.NetworkError(
                    message = firstResult.message,
                    fallbackLocations = getCuratedMatches(normalizedQuery)
                )
            }
            is NominatimFetchResult.Malformed -> {
                return@withContext LocationSearchResult.MalformedResponse(firstResult.message)
            }
            is NominatimFetchResult.Empty -> {
                // Primary country-biased search returned empty.
                // 2. Fall back to global search (no country filter) to not prevent searches outside India
                if (!defaultCountryCode.isNullOrBlank()) {
                    val globalResult = queryNominatim(primaryQuery, countryCode = null)
                    if (globalResult is NominatimFetchResult.Success) {
                        val ranked = PlaceResultRanker.rankResults(
                            locations = globalResult.locations,
                            query = normalizedQuery,
                            userLatitude = userLatitude,
                            userLongitude = userLongitude
                        )
                        return@withContext LocationSearchResult.Success(ranked)
                    }
                }

                // 3. If still empty, check alternative candidate queries (abbreviation/alias expansions)
                for (altQuery in candidates.drop(1)) {
                    val altResult = queryNominatim(altQuery, defaultCountryCode)
                    if (altResult is NominatimFetchResult.Success) {
                        val ranked = PlaceResultRanker.rankResults(
                            locations = altResult.locations,
                            query = normalizedQuery,
                            userLatitude = userLatitude,
                            userLongitude = userLongitude
                        )
                        return@withContext LocationSearchResult.Success(ranked)
                    }
                }

                return@withContext LocationSearchResult.Empty(query.trim())
            }
        }
    }

    private sealed class NominatimFetchResult {
        data class Success(val locations: List<SearchLocation>) : NominatimFetchResult()
        object Empty : NominatimFetchResult()
        data class NetworkError(val message: String) : NominatimFetchResult()
        data class Malformed(val message: String) : NominatimFetchResult()
    }

    private fun queryNominatim(queryStr: String, countryCode: String?): NominatimFetchResult {
        val encodedQuery = try {
            URLEncoder.encode(queryStr, "UTF-8")
        } catch (e: Exception) {
            queryStr
        }

        val countryParam = if (!countryCode.isNullOrBlank()) "&countrycodes=$countryCode" else ""
        val url = "$baseUrl?q=$encodedQuery&format=json&addressdetails=1&limit=10$countryParam"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MotoNav-Android-Companion/1.0 (contact: support@motonav.app)")
            .get()
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            return NominatimFetchResult.NetworkError("Unable to search locations. Check your internet connection.")
        } catch (e: Throwable) {
            return NominatimFetchResult.NetworkError("Unable to search locations. Check your internet connection.")
        }

        if (!response.isSuccessful) {
            return NominatimFetchResult.NetworkError("Unable to search locations. Check your internet connection.")
        }

        val bodyString = try {
            response.body?.string()
        } catch (e: Exception) {
            return NominatimFetchResult.NetworkError("Unable to search locations. Check your internet connection.")
        }

        if (bodyString.isNullOrBlank()) {
            return NominatimFetchResult.Empty
        }

        val jsonArray = try {
            JSONArray(bodyString)
        } catch (e: Exception) {
            return NominatimFetchResult.Malformed("Unable to parse search results. Please try again.")
        }

        if (jsonArray.length() == 0) {
            return NominatimFetchResult.Empty
        }

        val results = mutableListOf<SearchLocation>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue

            val latStr = obj.optString("lat")
            val lonStr = obj.optString("lon")
            val lat = latStr.toDoubleOrNull() ?: if (obj.has("lat")) obj.optDouble("lat", Double.NaN) else Double.NaN
            val lon = lonStr.toDoubleOrNull() ?: if (obj.has("lon")) obj.optDouble("lon", Double.NaN) else Double.NaN

            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                continue
            }

            val displayName = obj.optString("display_name", "").trim()
            val nameProp = obj.optString("name", "").trim()

            val primaryName = when {
                nameProp.isNotBlank() -> nameProp
                displayName.isNotBlank() -> displayName.split(",").firstOrNull()?.trim() ?: displayName
                else -> "Location ($lat, $lon)"
            }

            val address = if (displayName.isNotBlank()) displayName else primaryName
            val placeId = obj.optString("place_id", i.toString())
            val importance = obj.optDouble("importance", 0.0)
            val placeType = obj.optString("type", "")
            val category = obj.optString("class", "")

            results.add(
                SearchLocation(
                    id = "osm_$placeId",
                    name = primaryName,
                    address = address,
                    latitude = lat,
                    longitude = lon,
                    importance = importance,
                    placeType = placeType,
                    category = category
                )
            )
        }

        return if (results.isEmpty()) {
            NominatimFetchResult.Empty
        } else {
            NominatimFetchResult.Success(results)
        }
    }

    override suspend fun searchLocations(query: String): List<SearchLocation> {
        return when (val result = search(query)) {
            is LocationSearchResult.Success -> result.locations
            is LocationSearchResult.Empty -> emptyList()
            is LocationSearchResult.NetworkError -> result.fallbackLocations
            is LocationSearchResult.MalformedResponse -> emptyList()
        }
    }

    private fun getCuratedMatches(query: String): List<SearchLocation> {
        val clean = query.trim().lowercase()
        if (clean.isBlank()) return curatedLocations
        return curatedLocations.filter { loc ->
            loc.name.lowercase().contains(clean) || loc.address.lowercase().contains(clean)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://nominatim.openstreetmap.org/search"
        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        }
        val instance: DefaultLocationSearchRepository by lazy { DefaultLocationSearchRepository() }
    }
}
