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
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

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
 * Local store for curated places and recently selected search locations.
 */
class LocalLocationStore(
    curated: List<SearchLocation> = emptyList(),
    private val maxRecentSize: Int = 20
) {
    private val lock = Any()
    private val curatedList = curated.toList()
    private val recentLocations = mutableListOf<SearchLocation>()

    fun getCuratedLocations(): List<SearchLocation> = curatedList

    fun getRecentLocations(): List<SearchLocation> {
        synchronized(lock) {
            return recentLocations.toList()
        }
    }

    fun getAllLocations(): List<SearchLocation> {
        synchronized(lock) {
            return recentLocations.toList()
        }
    }

    fun recordRecent(location: SearchLocation) {
        if (!location.isValid()) return
        synchronized(lock) {
            recentLocations.removeAll { it.id == location.id || (it.latitude == location.latitude && it.longitude == location.longitude) }
            val recentCandidate = if (location.id.startsWith("recent_") || location.id.startsWith("loc_")) {
                location
            } else {
                location.copy(id = "recent_${location.id}")
            }
            recentLocations.add(0, recentCandidate)
            if (recentLocations.size > maxRecentSize) {
                recentLocations.removeAt(recentLocations.size - 1)
            }
        }
    }

    fun search(rawQuery: String): List<SearchLocation> {
        val clean = PlaceQueryNormalizer.normalize(rawQuery).lowercase(Locale.ROOT)
        if (clean.isBlank()) return emptyList()

        val all = getRecentLocations()
        if (all.isEmpty()) return emptyList()

        val tokens = clean.split(" ").filter { it.isNotBlank() }
        val queryVariants = PlaceQueryNormalizer.generateVariants(rawQuery)

        return all.filter { loc ->
            val nameLower = loc.name.lowercase(Locale.ROOT)
            val addrLower = loc.address.lowercase(Locale.ROOT)

            // 1. Exact or substring match
            if (nameLower.contains(clean) || addrLower.contains(clean)) return@filter true

            // 2. Query variant matches
            if (queryVariants.any { v ->
                val vl = v.lowercase(Locale.ROOT)
                nameLower.contains(vl) || addrLower.contains(vl)
            }) return@filter true

            // 3. Token & synonym overlap
            tokens.any { token ->
                val synonyms = PlaceQueryNormalizer.getSynonymsForToken(token)
                synonyms.any { s -> nameLower.contains(s) || addrLower.contains(s) }
            }
        }
    }
}

/**
 * Repository interface for searching locations without proprietary/paid APIs.
 */
interface LocationSearchRepository {
    suspend fun searchLocations(query: String): List<SearchLocation>
    suspend fun search(query: String): LocationSearchResult
    suspend fun search(query: String, userLatitude: Double?, userLongitude: Double?): LocationSearchResult = search(query)
    suspend fun reverseGeocode(latitude: Double, longitude: Double): String? = null
    fun getPopularLocations(): List<SearchLocation>
    fun searchLocal(query: String): List<SearchLocation> = emptyList()
    fun recordRecentLocation(location: SearchLocation) {}
    fun getSavedAndRecentLocations(): List<SearchLocation> = emptyList()
}

/**
 * Generalized search repository combining:
 * 1. Local saved & curated places store
 * 2. Primary Provider: Photon (location-biased, query variant expanded)
 * 3. Composite score ranking & strong-result evaluation
 * 4. Fallback Provider: OpenStreetMap Nominatim (location/viewbox-biased, rate-limited, LRU cached)
 * 5. Candidate merging and deduplication
 */
class DefaultLocationSearchRepository(
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val defaultCountryCode: String? = "in",
    private val minRequestIntervalMs: Long = DEFAULT_MIN_REQUEST_INTERVAL_MS,
    private val nanoTimeProvider: () -> Long = { System.nanoTime() },
    private val delayer: suspend (Long) -> Unit = { delay(it) },
    private val photonProvider: PlaceSearchProvider? = defaultPhotonProvider
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

    private val localStore = LocalLocationStore(curatedLocations)

    override fun getPopularLocations(): List<SearchLocation> = localStore.getCuratedLocations()

    override fun searchLocal(query: String): List<SearchLocation> = localStore.search(query)

    override fun recordRecentLocation(location: SearchLocation) = localStore.recordRecent(location)

    override fun getSavedAndRecentLocations(): List<SearchLocation> = localStore.getAllLocations()

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

        // 1. Search local saved/recents immediately and hold matching candidates
        val localMatches = searchLocal(normalizedQuery)

        val candidateQueries = PlaceQueryNormalizer.getCandidateQueries(query)
        val primaryQuery = candidateQueries.firstOrNull() ?: normalizedQuery

        currentCoroutineContext().ensureActive()

        var photonCandidates: List<SearchLocation>? = null

        // 2. Primary Provider: Photon (with geographic bias when coordinates are available)
        if (photonProvider != null) {
            try {
                currentCoroutineContext().ensureActive()
                val primaryResults = photonProvider.search(
                    query = primaryQuery,
                    lat = userLatitude,
                    lon = userLongitude,
                    limit = 10
                )
                if (primaryResults.isNotEmpty()) {
                    val rankedPhoton = PlaceResultRanker.rankResults(
                        locations = primaryResults,
                        query = normalizedQuery,
                        userLatitude = userLatitude,
                        userLongitude = userLongitude
                    )
                    if (isResultUseful(rankedPhoton, normalizedQuery, userLatitude, userLongitude)) {
                        val merged = deduplicateLocations(localMatches, rankedPhoton)
                        val finalRanked = PlaceResultRanker.rankResults(merged, normalizedQuery, userLatitude, userLongitude)
                        return@withContext LocationSearchResult.Success(finalRanked)
                    } else {
                        photonCandidates = rankedPhoton
                    }
                }

                // If primary Photon query produced weak/empty results, try bounded high-value variants
                val queryVariants = PlaceQueryNormalizer.generateVariants(query)
                    .filter { !it.equals(primaryQuery, ignoreCase = true) }
                    .take(2)

                for (variant in queryVariants) {
                    currentCoroutineContext().ensureActive()
                    val variantResults = photonProvider.search(
                        query = variant,
                        lat = userLatitude,
                        lon = userLongitude,
                        limit = 10
                    )
                    if (variantResults.isNotEmpty()) {
                        val combinedPhoton = if (!photonCandidates.isNullOrEmpty()) {
                            deduplicateLocations(photonCandidates, variantResults)
                        } else {
                            variantResults
                        }
                        val ranked = PlaceResultRanker.rankResults(
                            locations = combinedPhoton,
                            query = normalizedQuery,
                            userLatitude = userLatitude,
                            userLongitude = userLongitude
                        )
                        if (isResultUseful(ranked, normalizedQuery, userLatitude, userLongitude)) {
                            val merged = deduplicateLocations(localMatches, ranked)
                            val finalRanked = PlaceResultRanker.rankResults(merged, normalizedQuery, userLatitude, userLongitude)
                            return@withContext LocationSearchResult.Success(finalRanked)
                        } else {
                            photonCandidates = ranked
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Photon failure (429, 5xx, timeout, network error, malformed response)
                // Gracefully continue to Nominatim fallback
            }
        }

        currentCoroutineContext().ensureActive()

        // 3. Fallback Provider: OpenStreetMap Nominatim
        // (Preserves rate limiting, LRU query cache, geographic viewbox bias, and candidate expansions)
        when (val firstResult = queryNominatim(primaryQuery, defaultCountryCode, userLatitude, userLongitude)) {
            is NominatimFetchResult.Success -> {
                val combined = if (!photonCandidates.isNullOrEmpty()) {
                    deduplicateLocations(firstResult.locations, photonCandidates)
                } else {
                    firstResult.locations
                }
                val merged = deduplicateLocations(localMatches, combined)
                val ranked = PlaceResultRanker.rankResults(
                    locations = merged,
                    query = normalizedQuery,
                    userLatitude = userLatitude,
                    userLongitude = userLongitude
                )
                return@withContext LocationSearchResult.Success(ranked)
            }
            is NominatimFetchResult.RateLimited -> {
                val fallbackList = if (localMatches.isNotEmpty()) localMatches else getCuratedMatches(normalizedQuery)
                return@withContext LocationSearchResult.RateLimited(
                    message = firstResult.message,
                    fallbackLocations = fallbackList
                )
            }
            is NominatimFetchResult.NetworkError -> {
                val fallbackList = if (localMatches.isNotEmpty()) localMatches else getCuratedMatches(normalizedQuery)
                return@withContext LocationSearchResult.NetworkError(
                    message = firstResult.message,
                    fallbackLocations = fallbackList
                )
            }
            is NominatimFetchResult.Malformed -> {
                if (localMatches.isNotEmpty()) {
                    return@withContext LocationSearchResult.Success(localMatches)
                }
                return@withContext LocationSearchResult.MalformedResponse(firstResult.message)
            }
            is NominatimFetchResult.Empty -> {
                // Country-biased search returned empty.
                // 4. Fall back to global search (no country filter)
                if (!defaultCountryCode.isNullOrBlank()) {
                    currentCoroutineContext().ensureActive()
                    val globalResult = queryNominatim(primaryQuery, countryCode = null, userLatitude = userLatitude, userLongitude = userLongitude)
                    when (globalResult) {
                        is NominatimFetchResult.Success -> {
                            val combined = if (!photonCandidates.isNullOrEmpty()) {
                                deduplicateLocations(globalResult.locations, photonCandidates)
                            } else {
                                globalResult.locations
                            }
                            val merged = deduplicateLocations(localMatches, combined)
                            val ranked = PlaceResultRanker.rankResults(
                                locations = merged,
                                query = normalizedQuery,
                                userLatitude = userLatitude,
                                userLongitude = userLongitude
                            )
                            return@withContext LocationSearchResult.Success(ranked)
                        }
                        is NominatimFetchResult.RateLimited -> {
                            val fallbackList = if (localMatches.isNotEmpty()) localMatches else getCuratedMatches(normalizedQuery)
                            return@withContext LocationSearchResult.RateLimited(
                                message = globalResult.message,
                                fallbackLocations = fallbackList
                            )
                        }
                        else -> { /* continue to alternative candidates */ }
                    }
                }

                // 5. If still empty, check alternative candidate queries (abbreviation/alias/compound expansions)
                for (altQuery in candidateQueries.drop(1)) {
                    currentCoroutineContext().ensureActive()
                    val altResult = queryNominatim(altQuery, defaultCountryCode, userLatitude, userLongitude)
                    when (altResult) {
                        is NominatimFetchResult.Success -> {
                            val combined = if (!photonCandidates.isNullOrEmpty()) {
                                deduplicateLocations(altResult.locations, photonCandidates)
                            } else {
                                altResult.locations
                            }
                            val merged = deduplicateLocations(localMatches, combined)
                            val ranked = PlaceResultRanker.rankResults(
                                locations = merged,
                                query = normalizedQuery,
                                userLatitude = userLatitude,
                                userLongitude = userLongitude
                            )
                            return@withContext LocationSearchResult.Success(ranked)
                        }
                        is NominatimFetchResult.RateLimited -> {
                            val fallbackList = if (localMatches.isNotEmpty()) localMatches else getCuratedMatches(normalizedQuery)
                            return@withContext LocationSearchResult.RateLimited(
                                message = altResult.message,
                                fallbackLocations = fallbackList
                            )
                        }
                        else -> { /* continue */ }
                    }
                }

                currentCoroutineContext().ensureActive()

                // If any local matches or partial photon results exist, return them
                if (localMatches.isNotEmpty()) {
                    val ranked = PlaceResultRanker.rankResults(localMatches, normalizedQuery, userLatitude, userLongitude)
                    return@withContext LocationSearchResult.Success(ranked)
                }

                if (!photonCandidates.isNullOrEmpty()) {
                    return@withContext LocationSearchResult.Success(photonCandidates)
                }

                return@withContext LocationSearchResult.Empty(query.trim())
            }
        }
    }

    internal fun isResultUseful(
        locations: List<SearchLocation>,
        query: String,
        userLatitude: Double?,
        userLongitude: Double?,
        threshold: Double = PlaceResultRanker.DEFAULT_STRONG_RESULT_THRESHOLD
    ): Boolean {
        return PlaceResultRanker.hasStrongResult(locations, query, userLatitude, userLongitude, threshold)
    }

    internal fun deduplicateLocations(
        primary: List<SearchLocation>,
        secondary: List<SearchLocation>
    ): List<SearchLocation> {
        if (primary.isEmpty()) return secondary
        if (secondary.isEmpty()) return primary

        val result = primary.toMutableList()
        for (sec in secondary) {
            val dupIndex = result.indexOfFirst { prim ->
                val distKm = PlaceResultRanker.distanceBetweenKm(prim.latitude, prim.longitude, sec.latitude, sec.longitude)
                distKm < 0.030 || (distKm < 0.150 && areNamesSimilar(prim.name, sec.name))
            }
            if (dupIndex >= 0) {
                val existing = result[dupIndex]
                result[dupIndex] = selectBestCandidate(existing, sec)
            } else {
                result.add(sec)
            }
        }
        return result
    }

    private fun selectBestCandidate(first: SearchLocation, second: SearchLocation): SearchLocation {
        val elevation = if (first.elevationMeters > 0.0) first.elevationMeters else second.elevationMeters
        val importance = maxOf(first.importance, second.importance)
        val placeType = first.placeType.ifBlank { second.placeType }
        val category = first.category.ifBlank { second.category }

        // Keep local/curated title for rider familiarity if present, but enrich with longer address
        val name = if (first.id.startsWith("loc_") || first.id.startsWith("curated_")) {
            first.name
        } else if (second.id.startsWith("loc_") || second.id.startsWith("curated_")) {
            second.name
        } else if (first.name.length >= second.name.length) {
            first.name
        } else {
            second.name
        }

        val address = if (first.address.length >= second.address.length) first.address else second.address

        return first.copy(
            name = name,
            address = address,
            elevationMeters = elevation,
            importance = importance,
            placeType = placeType,
            category = category
        )
    }

    private fun areNamesSimilar(name1: String, name2: String): Boolean {
        val n1 = name1.trim().lowercase(Locale.ROOT)
        val n2 = name2.trim().lowercase(Locale.ROOT)
        return n1 == n2 || n1.contains(n2) || n2.contains(n1)
    }

    data class CacheKey(
        val normalizedQuery: String,
        val countryCode: String?,
        val viewboxKey: String? = null
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

    private suspend fun queryNominatim(
        queryStr: String,
        countryCode: String?,
        userLatitude: Double? = null,
        userLongitude: Double? = null
    ): NominatimFetchResult {
        currentCoroutineContext().ensureActive()

        val normalizedQuery = queryStr.trim().lowercase(Locale.ROOT)
        val normalizedCountry = countryCode?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }
        val viewboxKey = if (userLatitude != null && userLongitude != null &&
            userLatitude.isFinite() && userLongitude.isFinite()
        ) {
            String.format(Locale.ROOT, "%.2f,%.2f", userLatitude, userLongitude)
        } else {
            null
        }
        val cacheKey = CacheKey(normalizedQuery, normalizedCountry, viewboxKey)

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
        val viewboxParam = if (userLatitude != null && userLongitude != null &&
            userLatitude.isFinite() && userLongitude.isFinite()
        ) {
            val delta = 0.5
            val minLon = userLongitude - delta
            val maxLat = userLatitude + delta
            val maxLon = userLongitude + delta
            val minLat = userLatitude - delta
            "&viewbox=$minLon,$maxLat,$maxLon,$minLat"
        } else {
            ""
        }
        val url = "$baseUrl?q=$encodedQuery&format=json&addressdetails=1&limit=10$countryParam$viewboxParam"

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

    private val reverseCache = LinkedHashMap<Pair<Long, Long>, String>(64, 0.75f, true)
    private val reverseCacheLock = Any()

    override suspend fun reverseGeocode(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return@withContext null
        }

        val cacheKey = Pair(
            (latitude * 10000.0).roundToLong(),
            (longitude * 10000.0).roundToLong()
        )
        synchronized(reverseCacheLock) {
            reverseCache[cacheKey]?.let { return@withContext it }
        }

        // 1. Try Photon reverse geocoding
        val photonResult = tryPhotonReverse(latitude, longitude)
        if (!photonResult.isNullOrBlank()) {
            synchronized(reverseCacheLock) {
                reverseCache[cacheKey] = photonResult
                if (reverseCache.size > 64) {
                    val eldest = reverseCache.keys.iterator().next()
                    reverseCache.remove(eldest)
                }
            }
            return@withContext photonResult
        }

        // 2. Try Nominatim reverse geocoding
        val nominatimResult = tryNominatimReverse(latitude, longitude)
        if (!nominatimResult.isNullOrBlank()) {
            synchronized(reverseCacheLock) {
                reverseCache[cacheKey] = nominatimResult
                if (reverseCache.size > 64) {
                    val eldest = reverseCache.keys.iterator().next()
                    reverseCache.remove(eldest)
                }
            }
            return@withContext nominatimResult
        }

        null
    }

    private fun tryPhotonReverse(latitude: Double, longitude: Double): String? {
        val url = "https://photon.komoot.io/reverse?lat=$latitude&lon=$longitude"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MotoNav/1.0 (Android Motorcycle Navigation)")
            .get()
            .build()
        return try {
            val responseBody = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            } ?: return null
            val json = JSONObject(responseBody)
            val features = json.optJSONArray("features") ?: return null
            if (features.length() == 0) return null
            val props = features.getJSONObject(0).optJSONObject("properties") ?: return null

            val name = props.optString("name").takeIf { it.isNotBlank() }
            val street = props.optString("street").takeIf { it.isNotBlank() }
            val city = props.optString("city").ifBlank {
                props.optString("town").ifBlank {
                    props.optString("village")
                }
            }.takeIf { it.isNotBlank() }
            val district = props.optString("district").ifBlank {
                props.optString("suburb").ifBlank {
                    props.optString("county")
                }
            }.takeIf { it.isNotBlank() }

            buildFormattedAddress(name, street, city, district)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }
    }

    private fun tryNominatimReverse(latitude: Double, longitude: Double): String? {
        val url = "https://nominatim.openstreetmap.org/reverse?lat=$latitude&lon=$longitude&format=jsonv2"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MotoNav/1.0 (Android Motorcycle Navigation)")
            .get()
            .build()
        return try {
            val responseBody = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            } ?: return null
            val json = JSONObject(responseBody)
            val name = json.optString("name").takeIf { it.isNotBlank() }
            val addr = json.optJSONObject("address")
            val street = addr?.optString("road")?.takeIf { it.isNotBlank() }
            val city = addr?.optString("city")?.ifBlank {
                addr.optString("town").ifBlank {
                    addr.optString("village")
                }
            }?.takeIf { it.isNotBlank() }
            val district = addr?.optString("suburb")?.ifBlank {
                addr.optString("neighbourhood")
            }?.takeIf { it.isNotBlank() }

            val formatted = buildFormattedAddress(name, street, city, district)
            formatted ?: json.optString("display_name").takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }
    }

    private fun buildFormattedAddress(name: String?, street: String?, city: String?, district: String?): String? {
        val parts = mutableListOf<String>()
        if (!name.isNullOrBlank()) {
            parts.add(name)
        }
        if (!street.isNullOrBlank() && (parts.isEmpty() || !parts[0].equals(street, ignoreCase = true))) {
            parts.add(street)
        }
        val locality = city ?: district
        if (!locality.isNullOrBlank() && !parts.any { it.equals(locality, ignoreCase = true) }) {
            parts.add(locality)
        }
        return if (parts.isNotEmpty()) parts.joinToString(", ") else null
    }

    private fun getCuratedMatches(query: String): List<SearchLocation> {
        val clean = PlaceQueryNormalizer.normalize(query).lowercase(Locale.ROOT)
        if (clean.isBlank()) return curatedLocations
        val tokens = clean.split(" ").filter { it.isNotBlank() }
        val matches = curatedLocations.filter { loc ->
            val nameLower = loc.name.lowercase(Locale.ROOT)
            val addrLower = loc.address.lowercase(Locale.ROOT)
            nameLower.contains(clean) || addrLower.contains(clean) ||
                tokens.any { t -> nameLower.contains(t) || addrLower.contains(t) }
        }
        return matches.ifEmpty { curatedLocations }
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
        val defaultPhotonProvider: PlaceSearchProvider by lazy { PhotonSearchProvider() }
        val instance: DefaultLocationSearchRepository by lazy { DefaultLocationSearchRepository(photonProvider = defaultPhotonProvider) }
    }
}
