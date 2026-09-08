package com.example.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class ValhallaRouteRequest(
    @Json(name = "locations")
    val locations: List<ValhallaLocation>,
    @Json(name = "costing")
    val costing: String = "motorcycle",
    @Json(name = "costing_options")
    val costingOptions: Map<String, Any>? = null,
    @Json(name = "directions_options")
    val directionsOptions: ValhallaDirectionsOptions? = ValhallaDirectionsOptions(units = "kilometers")
)

@JsonClass(generateAdapter = true)
data class ValhallaLocation(
    @Json(name = "lat")
    val lat: Double,
    @Json(name = "lon")
    val lon: Double,
    @Json(name = "type")
    val type: String? = "break",
    @Json(name = "heading")
    val heading: Double? = null,
    @Json(name = "side_of_street")
    val sideOfStreet: String? = null
)

@JsonClass(generateAdapter = true)
data class ValhallaDirectionsOptions(
    @Json(name = "units")
    val units: String = "kilometers",
    @Json(name = "language")
    val language: String? = "en-US"
)

@JsonClass(generateAdapter = true)
data class ValhallaRouteResponse(
    @Json(name = "trip")
    val trip: ValhallaTrip? = null,
    @Json(name = "status")
    val status: Int? = null,
    @Json(name = "status_message")
    val statusMessage: String? = null
)

@JsonClass(generateAdapter = true)
data class ValhallaTrip(
    @Json(name = "locations")
    val locations: List<ValhallaLocation>? = null,
    @Json(name = "legs")
    val legs: List<ValhallaLeg>? = null,
    @Json(name = "summary")
    val summary: ValhallaSummary? = null,
    @Json(name = "status")
    val status: Int? = null,
    @Json(name = "status_message")
    val statusMessage: String? = null,
    @Json(name = "units")
    val units: String? = null
)

@JsonClass(generateAdapter = true)
data class ValhallaLeg(
    @Json(name = "shape")
    val shape: String? = null,
    @Json(name = "summary")
    val summary: ValhallaSummary? = null,
    @Json(name = "maneuvers")
    val maneuvers: List<ValhallaManeuver>? = null
)

@JsonClass(generateAdapter = true)
data class ValhallaSummary(
    @Json(name = "time")
    val time: Double? = null,
    @Json(name = "length")
    val length: Double? = null,
    @Json(name = "min_lat")
    val minLat: Double? = null,
    @Json(name = "min_lon")
    val minLon: Double? = null,
    @Json(name = "max_lat")
    val maxLat: Double? = null,
    @Json(name = "max_lon")
    val maxLon: Double? = null
)

@JsonClass(generateAdapter = true)
data class ValhallaManeuver(
    @Json(name = "type")
    val type: Int,
    @Json(name = "instruction")
    val instruction: String? = null,
    @Json(name = "verbal_succinct_transition_instruction")
    val verbalSuccinctTransitionInstruction: String? = null,
    @Json(name = "verbal_pre_transition_instruction")
    val verbalPreTransitionInstruction: String? = null,
    @Json(name = "verbal_post_transition_instruction")
    val verbalPostTransitionInstruction: String? = null,
    @Json(name = "street_names")
    val streetNames: List<String>? = null,
    @Json(name = "time")
    val time: Double? = null,
    @Json(name = "length")
    val length: Double? = null, // in km when units = "kilometers"
    @Json(name = "cost")
    val cost: Double? = null,
    @Json(name = "begin_shape_index")
    val beginShapeIndex: Int = 0,
    @Json(name = "end_shape_index")
    val endShapeIndex: Int = 0,
    @Json(name = "verbal_transition_alert_instruction")
    val verbalTransitionAlertInstruction: String? = null,
    @Json(name = "bearing_before")
    val bearingBefore: Int? = null,
    @Json(name = "bearing_after")
    val bearingAfter: Int? = null,
    @Json(name = "roundabout_exit_count")
    val roundaboutExitCount: Int? = null
)

interface ValhallaApi {
    @POST("route")
    suspend fun getRoute(@Body request: ValhallaRouteRequest): ValhallaRouteResponse

    companion object {
        const val DEFAULT_BASE_URL = "https://valhalla1.openstreetmap.de/"

        fun create(
            baseUrl: String = DEFAULT_BASE_URL,
            okHttpClient: OkHttpClient? = null
        ): ValhallaApi {
            val client = okHttpClient ?: OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    }
                )
                .build()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(ValhallaApi::class.java)
        }
    }
}
