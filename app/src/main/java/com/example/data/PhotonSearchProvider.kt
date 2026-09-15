package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

/**
 * Production PlaceSearchProvider backed by the Photon geocoding service (Elasticsearch/OSM).
 *
 * Provides fast, fuzzy, and location-biased place resolution.
 */
class PhotonSearchProvider(
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val minRequestIntervalMs: Long = DEFAULT_MIN_REQUEST_INTERVAL_MS,
    private val cacheMaxSize: Int = DEFAULT_CACHE_MAX_SIZE,
    private val nanoTimeProvider: () -> Long = { System.nanoTime() },
    private val delayer: suspend (Long) -> Unit = { delay(it) }
) : PlaceSearchProvider {

    override val providerName: String = "photon"

    private data class CacheKey(
        val query: String,
        val latBucket: Long?,
        val lonBucket: Long?
    )

    private class LruCache<K, V>(private val maxSize: Int) {
        private val lock = Any()
        private val map = LinkedHashMap<K, V>(maxSize, 0.75f, true)

        fun get(key: K): V? = synchronized(lock) { map[key] }

        fun put(key: K, value: V) {
            synchronized(lock) {
                map[key] = value
                if (map.size > maxSize) {
                    val eldest = map.keys.iterator().next()
                    map.remove(eldest)
                }
            }
        }

        fun clear() = synchronized(lock) { map.clear() }
        fun size(): Int = synchronized(lock) { map.size }
    }

    private val cache = LruCache<CacheKey, List<SearchLocation>>(cacheMaxSize)
    private val rateLimitMutex = Mutex()
    private var lastDispatchNanoTime: Long = 0L

    internal fun clearCache() = cache.clear()
    internal fun cacheSize(): Int = cache.size()

    private suspend fun paceRequest() {
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

    override suspend fun search(
        query: String,
        lat: Double?,
        lon: Double?,
        limit: Int
    ): List<SearchLocation> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val cleanQuery = PlaceQueryNormalizer.normalize(query).trim().lowercase(Locale.ROOT)
        if (cleanQuery.isBlank()) {
            return@withContext emptyList()
        }

        val latBucket = if (lat != null && lat.isFinite()) (lat * 100.0).roundToLong() else null
        val lonBucket = if (lon != null && lon.isFinite()) (lon * 100.0).roundToLong() else null
        val cacheKey = CacheKey(cleanQuery, latBucket, lonBucket)

        val cached = cache.get(cacheKey)
        if (cached != null) {
            return@withContext cached
        }

        paceRequest()
        currentCoroutineContext().ensureActive()

        val encodedQuery = try {
            URLEncoder.encode(cleanQuery, "UTF-8")
        } catch (e: Exception) {
            cleanQuery
        }

        val urlBuilder = StringBuilder(baseUrl)
            .append("?q=").append(encodedQuery)
            .append("&limit=").append(limit.coerceIn(1, 20))

        if (lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0
        ) {
            urlBuilder.append("&lat=").append(String.format(Locale.US, "%.6f", lat))
            urlBuilder.append("&lon=").append(String.format(Locale.US, "%.6f", lon))
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("User-Agent", "MotoNav-Android/1.0 (contact: support@motonav.app)")
            .get()
            .build()

        val call = httpClient.newCall(request)

        val responseBody = try {
            suspendCancellableCoroutine<String?> { continuation ->
                continuation.invokeOnCancellation {
                    call.cancel()
                }

                try {
                    continuation.context.ensureActive()
                    val body = call.execute().use { response ->
                        when {
                            response.code == 429 -> {
                                throw PlaceSearchException.RateLimited(429, "Photon rate limit exceeded (HTTP 429)")
                            }
                            response.code in 500..599 -> {
                                throw PlaceSearchException.ServerError(response.code, "Photon server error (HTTP ${response.code})")
                            }
                            !response.isSuccessful -> {
                                throw PlaceSearchException.ServerError(response.code, "Photon unexpected response (HTTP ${response.code})")
                            }
                            else -> response.body?.string()
                        }
                    }

                    if (call.isCanceled() || !continuation.isActive) {
                        continuation.cancel(CancellationException("Photon search call cancelled"))
                    } else {
                        continuation.resume(body)
                    }
                } catch (e: Throwable) {
                    if (call.isCanceled() || !continuation.isActive) {
                        continuation.cancel(e as? CancellationException ?: CancellationException("Photon search call cancelled", e))
                    } else {
                        continuation.resumeWithException(e)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlaceSearchException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw PlaceSearchException.Timeout("Photon search request timed out")
        } catch (e: IOException) {
            throw PlaceSearchException.NetworkError("Photon network connection error", e)
        } catch (e: Throwable) {
            throw PlaceSearchException.NetworkError("Photon unexpected error", e)
        }

        currentCoroutineContext().ensureActive()

        if (responseBody.isNullOrBlank()) {
            return@withContext emptyList()
        }

        val parsedLocations = parsePhotonGeoJson(responseBody)

        // Only cache successful non-empty results
        if (parsedLocations.isNotEmpty()) {
            cache.put(cacheKey, parsedLocations)
        }

        return@withContext parsedLocations
    }

    internal fun parsePhotonGeoJson(jsonString: String): List<SearchLocation> {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty() || trimmed == "[]") {
            return emptyList()
        }
        val root = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            throw PlaceSearchException.MalformedResponse("Unable to parse Photon GeoJSON response", e)
        }

        val features = root.optJSONArray("features") ?: return emptyList()
        val results = mutableListOf<SearchLocation>()

        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coordinates = geometry.optJSONArray("coordinates") ?: continue

            // GeoJSON coordinates format is [longitude, latitude]
            val lon = coordinates.optDouble(0, Double.NaN)
            val lat = coordinates.optDouble(1, Double.NaN)

            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                continue
            }

            val props = feature.optJSONObject("properties") ?: JSONObject()
            val rawName = props.optString("name", "").trim()
            val street = props.optString("street", "").trim().ifEmpty { null }
            val district = props.optString("district", "").trim().ifEmpty { null }
            val city = props.optString("city", "").trim().ifEmpty {
                props.optString("locality", "").trim().ifEmpty { null }
            }
            val state = props.optString("state", "").trim().ifEmpty { null }
            val country = props.optString("country", "").trim().ifEmpty { null }
            val osmKey = props.optString("osm_key", "").trim().ifEmpty { null }
            val osmValue = props.optString("osm_value", "").trim().ifEmpty {
                props.optString("type", "").trim().ifEmpty { null }
            }
            val osmId = props.opt("osm_id")?.toString()?.trim()

            val primaryName = when {
                rawName.isNotBlank() -> rawName
                street != null -> street
                district != null -> district
                city != null -> city
                else -> "Location ($lat, $lon)"
            }

            val addressTokens = listOfNotNull(
                primaryName.ifEmpty { null },
                street,
                district,
                city,
                state,
                country
            ).filter { it.isNotBlank() }

            val fullAddress = if (addressTokens.isNotEmpty()) {
                addressTokens.distinct().joinToString(", ")
            } else {
                primaryName
            }

            // Estimate importance signal based on OSM key/value
            val importance = when (osmKey) {
                "place" -> if (osmValue == "city" || osmValue == "town") 0.8 else 0.5
                "amenity" -> if (osmValue == "hospital" || osmValue == "bus_station") 0.7 else 0.5
                "railway" -> 0.75
                "aeroway" -> 0.8
                "tourism" -> 0.65
                "historic" -> 0.6
                "highway" -> if (osmValue == "primary" || osmValue == "trunk") 0.6 else 0.4
                else -> 0.4
            }

            val locationId = "photon_${osmId ?: "${lat}_${lon}"}"

            results.add(
                SearchLocation(
                    id = locationId,
                    name = primaryName,
                    address = fullAddress,
                    latitude = lat,
                    longitude = lon,
                    importance = importance,
                    placeType = osmValue ?: "",
                    category = osmKey ?: ""
                )
            )
        }

        return results
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://photon.komoot.io/api"
        const val DEFAULT_MIN_REQUEST_INTERVAL_MS: Long = 250L
        const val DEFAULT_CACHE_MAX_SIZE: Int = 50

        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        }
    }
}
