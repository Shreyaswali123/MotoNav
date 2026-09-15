package com.example.data

/**
 * Common abstraction for place search providers (Photon, Nominatim).
 */
interface PlaceSearchProvider {
    val providerName: String

    /**
     * Executes a place search against the provider.
     *
     * @param query The search query string.
     * @param lat Optional latitude for geographic relevance biasing.
     * @param lon Optional longitude for geographic relevance biasing.
     * @param limit Maximum number of candidates to retrieve.
     * @return List of parsed and validated [SearchLocation] items.
     * @throws PlaceSearchException on HTTP errors, timeouts, network failures, or malformed responses.
     */
    suspend fun search(
        query: String,
        lat: Double?,
        lon: Double?,
        limit: Int = 10
    ): List<SearchLocation>
}

/**
 * Structured exceptions thrown by [PlaceSearchProvider] implementations to allow
 * deterministic error handling and provider fallback.
 */
sealed class PlaceSearchException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RateLimited(val code: Int = 429, message: String = "Search service rate limit exceeded") : PlaceSearchException(message)
    class ServerError(val code: Int, message: String) : PlaceSearchException(message)
    class NetworkError(message: String = "Network connection failure", cause: Throwable? = null) : PlaceSearchException(message, cause)
    class MalformedResponse(message: String = "Malformed response from search provider", cause: Throwable? = null) : PlaceSearchException(message, cause)
    class Timeout(message: String = "Search request timed out") : PlaceSearchException(message)
}
