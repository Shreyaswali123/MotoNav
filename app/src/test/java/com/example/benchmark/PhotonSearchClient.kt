package com.example.benchmark

import com.example.data.DefaultLocationSearchRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

sealed class PhotonSearchResult {
    data class Success(
        val locations: List<PhotonLocation>,
        val httpStatus: Int,
        val latencyMs: Long
    ) : PhotonSearchResult()

    data class Empty(
        val httpStatus: Int,
        val latencyMs: Long
    ) : PhotonSearchResult()

    data class Error(
        val message: String,
        val httpStatus: Int,
        val latencyMs: Long
    ) : PhotonSearchResult()
}

/**
 * Secondary benchmark-only Photon search client.
 * Does NOT modify or intercept the production search pipeline.
 */
class PhotonSearchClient(
    private val httpClient: OkHttpClient = DefaultLocationSearchRepository.defaultHttpClient,
    private val baseUrl: String = DEFAULT_PHOTON_URL
) {
    fun search(
        query: String,
        latitude: Double? = null,
        longitude: Double? = null,
        limit: Int = 5,
        lang: String = "en"
    ): PhotonSearchResult {
        val encodedQuery = try {
            URLEncoder.encode(query.trim(), "UTF-8")
        } catch (e: Exception) {
            query.trim()
        }

        val urlBuilder = StringBuilder("$baseUrl?q=$encodedQuery&limit=$limit&lang=$lang")
        if (latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite()) {
            urlBuilder.append("&lat=$latitude&lon=$longitude")
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("User-Agent", "MotoNav-Benchmark/1.0 (companion-test)")
            .get()
            .build()

        val startTime = System.nanoTime()
        val call = httpClient.newCall(request)

        return try {
            call.execute().use { response ->
                val elapsedMs = (System.nanoTime() - startTime) / 1_000_000L
                val statusCode = response.code

                if (!response.isSuccessful) {
                    return PhotonSearchResult.Error(
                        message = "HTTP $statusCode: ${response.message}",
                        httpStatus = statusCode,
                        latencyMs = elapsedMs
                    )
                }

                val body = response.body?.string() ?: ""
                val locations = PhotonParser.parse(body)

                if (locations.isEmpty()) {
                    PhotonSearchResult.Empty(httpStatus = statusCode, latencyMs = elapsedMs)
                } else {
                    PhotonSearchResult.Success(
                        locations = locations,
                        httpStatus = statusCode,
                        latencyMs = elapsedMs
                    )
                }
            }
        } catch (e: IOException) {
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000L
            PhotonSearchResult.Error(
                message = e.message ?: "Network error",
                httpStatus = 0,
                latencyMs = elapsedMs
            )
        } catch (e: Throwable) {
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000L
            PhotonSearchResult.Error(
                message = e.message ?: "Unexpected error",
                httpStatus = 0,
                latencyMs = elapsedMs
            )
        }
    }

    companion object {
        const val DEFAULT_PHOTON_URL = "https://photon.komoot.io/api"
    }
}
