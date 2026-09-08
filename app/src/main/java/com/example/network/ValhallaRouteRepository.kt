package com.example.network

import com.example.model.RoutePoint
import com.example.route.MotoNavRouteConverter
import com.example.route.RouteConversionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for fetching real motorcycle routes from a Valhalla routing engine
 * and converting them into serialized MotoNav v1 format.
 */
open class ValhallaRouteRepository(
    private val api: ValhallaApi = ValhallaApi.create()
) {

    /**
     * Requests a motorcycle route between [origin] and [destination] and processes
     * the shape and maneuvers through the MotoNav conversion pipeline.
     *
     * @param origin Starting coordinate.
     * @param destination Target coordinate.
     * @param routeId Route identifier for MotoNav header.
     * @return [Result] containing [RouteConversionResult] with serialized binary, CRC, and metadata.
     */
    open suspend fun fetchRoute(
        origin: RoutePoint,
        destination: RoutePoint,
        routeId: Long = 1L
    ): Result<RouteConversionResult> = withContext(Dispatchers.IO) {
        try {
            val request = ValhallaRouteRequest(
                locations = listOf(
                    ValhallaLocation(
                        lat = origin.latitude,
                        lon = origin.longitude,
                        type = "break"
                    ),
                    ValhallaLocation(
                        lat = destination.latitude,
                        lon = destination.longitude,
                        type = "break"
                    )
                ),
                costing = "motorcycle",
                directionsOptions = ValhallaDirectionsOptions(
                    units = "kilometers",
                    language = "en-US"
                )
            )

            val response = api.getRoute(request)

            if (response.trip == null) {
                return@withContext Result.failure(
                    IllegalStateException(
                        response.statusMessage ?: "Valhalla returned no trip (status=${response.status})"
                    )
                )
            }

            val conversion = MotoNavRouteConverter.convert(
                response = response,
                routeId = routeId
            )

            Result.success(conversion)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    companion object {
        val instance: ValhallaRouteRepository by lazy { ValhallaRouteRepository() }
    }
}
