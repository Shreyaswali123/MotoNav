package com.example.data

import com.example.model.RoutePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

const val DEFAULT_RATE_LIMITED_MESSAGE = "Search service is temporarily busy. Please wait a moment and try again."

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
    data class RateLimited(
        val message: String = DEFAULT_RATE_LIMITED_MESSAGE,
        val fallbackLocations: List<SearchLocation> = emptyList()
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
    private val defaultCountryCode: String? = "in",
    private val minRequestIntervalMs: Long = DEFAULT_MIN_REQUEST_INTERVAL_MS,
    private val nanoTimeProvider: () -> Long = { System.nanoTime() },
    private val delayer: suspend (Long) -> Unit = { delay(it) }
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
        currentCoroutineContext().ensureActive()
        val normalizedQuery = PlaceQueryNormalizer.normalize(query)
        if (normalizedQuery.isBlank()) {
            return@withContext LocationSearchResult.Success(curatedLocations)
        }

        val candidates = PlaceQueryNormalizer.getCandidateQueries(query)
        val primaryQuery = candidates.firstOrNull() ?: normalizedQuery

        currentCoroutineContext().ensureActive()

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
            is NominatimFetchResult.RateLimited -> {
                return@withContext LocationSearchResult.RateLimited(
                    message = firstResult.message,
                    fallbackLocations = getCuratedMatches(normalizedQuery)
                )
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
                    currentCoroutineContext().ensureActive()
                    val globalResult = queryNominatim(primaryQuery, countryCode = null)
                    when (globalResult) {
                        is NominatimFetchResult.Success -> {
                            val ranked = PlaceResultRanker.rankResults(
                                locations = globalResult.locations,
                                query = normalizedQuery,
                                userLatitude = userLatitude,
                                userLongitude = userLongitude
                            )
                            return@withContext LocationSearchResult.Success(ranked)
                        }
                        is NominatimFetchResult.RateLimited -> {
                            return@withContext LocationSearchResult.RateLimited(
                                message = globalResult.message,
                                fallbackLocations = getCuratedMatches(normalizedQuery)
                            )
                        }
                        else -> { /* continue to alternative candidates */ }
                    }
                }

                // 3. If still empty, check alternative candidate queries (abbreviation/alias expansions)
                for (altQuery in candidates.drop(1)) {
                    currentCoroutineContext().ensureActive()
                    val altResult = queryNominatim(altQuery, defaultCountryCode)
                    when (altResult) {
                        is NominatimFetchResult.Success -> {
                            val ranked = PlaceResultRanker.rankResults(
                                locations = altResult.locations,
                                query = normalizedQuery,
                                userLatitude = userLatitude,
                                userLongitude = userLongitude
                            )
                            return@withContext LocationSearchResult.Success(ranked)
                        }
                        is NominatimFetchResult.RateLimited -> {
                            return@withContext LocationSearchResult.RateLimited(
                                message = altResult.message,
                                fallbackLocations = getCuratedMatches(normalizedQuery)
                            )
                        }
                        else -> { /* continue */ }
                    }
                }

                currentCoroutineContext().ensureActive()
                return@withContext LocationSearchResult.Empty(query.trim())
            }
        }
    }

    data class CacheKey(
        val normalizedQuery: String,
        val countryCode: String?
    )

    private class LruQueryCache(private val maxSize: Int = DEFAULT_CACHE_MAX_SIZE) {
        private val lock = Any()
        private val map = LinkedHashMap<CacheKey, NominatimFetchResult>(maxSize, 0.75f, true)

        fun get(key: CacheKey): NominatimFetchResult? {
            synchronized(lock) {
                return map[key]
            }
        }

        fun put(key: CacheKey, result: NominatimFetchResult) {
            synchronized(lock) {
                map[key] = result
                if (map.size > maxSize) {
                    val eldestKey = map.keys.iterator().next()
                    map.remove(eldestKey)
                }
            }
        }

        fun size(): Int {
            synchronized(lock) {
                return map.size
            }
        }

        fun clear() {
            synchronized(lock) {
                map.clear()
            }
        }
    }

    private val queryCache = LruQueryCache(DEFAULT_CACHE_MAX_SIZE)
    private val rateLimitMutex = Mutex()
    private var lastDispatchNanoTime: Long = 0L

    internal fun clearCache() = queryCache.clear()
    internal fun cacheSize(): Int = queryCache.size()
    internal fun lastDispatchNanoTime(): Long = lastDispatchNanoTime

    private suspend fun paceNominatimRequest() {
        rateLimitMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (minRequestIntervalMs > 0) {
                val now = nanoTimeProvider()
                val minIntervalNanos = TimeUnit.MILLISECONDS.toNanos(minRequestIntervalMs)
                val elapsedNanos = now - lastDispatchNanoTime
                if (lastDispatchNanoTime != 0L && elapsedNanos < minIntervalNanos) {
                    val delayNanos = minIntervalNanos - elapsedNanos
                    val delayMs = TimeUnit.NANOSECONDS.toMillis(delayNanos)
                    val effectiveDelayMs = if (delayNanos % 1_000_000L != 0L) delayMs + 1 else delayMs
                    if (effectiveDelayMs > 0) {
                        delayer(effectiveDelayMs)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            lastDispatchNanoTime = nanoTimeProvider()
        }
    }

    private sealed class NominatimFetchResult {
        data class Success(val locations: List<SearchLocation>) : NominatimFetchResult()
        object Empty : NominatimFetchResult()
        data class NetworkError(val message: String) : NominatimFetchResult()
        data class Malformed(val message: String) : NominatimFetchResult()
        data class RateLimited(val message: String = DEFAULT_RATE_LIMITED_MESSAGE) : NominatimFetchResult()
    }

    private class HttpStatusException(val code: Int) : Exception("HTTP $code")

    private suspend fun queryNominatim(queryStr: String, countryCode: String?): NominatimFetchResult {
        currentCoroutineContext().ensureActive()

        val normalizedQuery = queryStr.trim().lowercase()
        val normalizedCountry = countryCode?.trim()?.lowercase()?.ifEmpty { null }
        val cacheKey = CacheKey(normalizedQuery, normalizedCountry)

        // 1. Check in-memory query cache first (no rate limiting, no network IO)
        val cached = queryCache.get(cacheKey)
        if (cached != null) {
            currentCoroutineContext().ensureActive()
            return cached
        }

        // 2. Pace outbound HTTP request to satisfy global minimum interval (1 req/sec)
        paceNominatimRequest()
        currentCoroutineContext().ensureActive()

        val encodedQuery = try {
            URLEncoder.encode(queryStr, "UTF-8")
        } catch (e: Exception) {
            queryStr
        }

        val countryParam = if (!normalizedCountry.isNullOrBlank()) "&countrycodes=$normalizedCountry" else ""
        val url = "$baseUrl?q=$encodedQuery&format=json&addressdetails=1&limit=10$countryParam"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MotoNav-Android-Companion/1.0 (contact: support@motonav.app)")
            .get()
            .build()

        currentCoroutineContext().ensureActive()
        val call = httpClient.newCall(request)

        val bodyString = try {
            currentCoroutineContext().ensureActive()
            suspendCancellableCoroutine<String?> { continuation ->
                continuation.invokeOnCancellation {
                    call.cancel()
                }

                try {
                    continuation.context.ensureActive()
                    val body = call.execute().use { response ->
                        if (response.code == 429) {
                            throw HttpStatusException(429)
                        }
                        if (!response.isSuccessful) {
                            throw HttpStatusException(response.code)
                        }
                        response.body?.string()
                    }

                    if (call.isCanceled() || !continuation.isActive) {
                        continuation.cancel(CancellationException("Search call cancelled"))
                    } else {
                        continuation.resume(body)
                    }
                } catch (e: Throwable) {
                    if (call.isCanceled() || !continuation.isActive) {
                        continuation.cancel(e as? CancellationException ?: CancellationException("Search call cancelled", e))
                    } else {
                        continuation.resumeWithException(e)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            if (e.code == 429) {
                return NominatimFetchResult.RateLimited()
            }
            return NominatimFetchResult.NetworkError("Unable to search locations. Check your internet connection.")
        } catch (e: IOException) {
            return NominatimFetchResult.NetworkError("Unable to search locations. Check your internet connection.")
        } catch (e: Throwable) {
            return NominatimFetchResult.NetworkError("Unable to search locations. Check your internet connection.")
        }

        currentCoroutineContext().ensureActive()

        val parsedResult = if (bodyString.isNullOrBlank()) {
            NominatimFetchResult.Empty
        } else {
            parseNominatimJson(bodyString)
        }

        // Cache successful and empty results under synchronization.
        // Do NOT cache NetworkError, Malformed, or cancellations.
        if (parsedResult is NominatimFetchResult.Success || parsedResult is NominatimFetchResult.Empty) {
            queryCache.put(cacheKey, parsedResult)
        }

        return parsedResult
    }

    private fun parseNominatimJson(bodyString: String): NominatimFetchResult {
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
            is LocationSearchResult.RateLimited -> result.fallbackLocations
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
        const val DEFAULT_MIN_REQUEST_INTERVAL_MS: Long = 1000L
        const val DEFAULT_CACHE_MAX_SIZE: Int = 50

        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        }
        val instance: DefaultLocationSearchRepository by lazy { DefaultLocationSearchRepository() }
    }
}
