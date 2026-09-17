package com.example.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Result representing phone GPS current location acquisition.
 */
sealed interface LocationResult {
    data class Success(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float? = null,
        val timeMs: Long? = null
    ) : LocationResult

    object PermissionDenied : LocationResult
    data class Unavailable(val message: String = "Unable to get your current location.") : LocationResult
}

/**
 * Android runtime location permission helper.
 */
object LocationPermissions {
    val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun hasLocationPermission(context: Context): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }
}

/**
 * Contract for acquiring a single fresh current location from device sensors.
 */
interface CurrentLocationProvider {
    suspend fun getCurrentLocation(): LocationResult
}

/**
 * Standard Android implementation using [LocationManager] and [LocationManagerCompat].
 * Requires zero proprietary SDKs, respects coroutine cancellation, and validates
 * geographic coordinate ranges.
 */
class AndroidCurrentLocationProvider(
    private val context: Context?
) : CurrentLocationProvider {

    override suspend fun getCurrentLocation(): LocationResult {
        val ctx = context ?: return LocationResult.Unavailable("Unable to get your current location.")

        if (!LocationPermissions.hasLocationPermission(ctx)) {
            return LocationResult.PermissionDenied
        }

        val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return LocationResult.Unavailable("Unable to get your current location.")

        val isGpsEnabled = try { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
        val isNetworkEnabled = try { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false }

        if (!isGpsEnabled && !isNetworkEnabled) {
            return LocationResult.Unavailable("Unable to get your current location.")
        }

        val location: Location? = try {
            withTimeoutOrNull(10000L) {
                fetchLocationFromManager(ctx, locationManager, isGpsEnabled, isNetworkEnabled)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }

        if (location == null) {
            return LocationResult.Unavailable("Unable to get your current location.")
        }

        val lat = location.latitude
        val lon = location.longitude
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return LocationResult.Unavailable("Unable to get your current location.")
        }

        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val time = location.time

        return LocationResult.Success(
            latitude = lat,
            longitude = lon,
            accuracyMeters = accuracy,
            timeMs = time
        )
    }

    private suspend fun fetchLocationFromManager(
        context: Context,
        locationManager: LocationManager,
        isGpsEnabled: Boolean,
        isNetworkEnabled: Boolean
    ): Location? = suspendCancellableCoroutine { continuation ->
        val cancellationSignal = androidx.core.os.CancellationSignal()
        continuation.invokeOnCancellation {
            cancellationSignal.cancel()
        }

        val preferredProvider = if (isGpsEnabled) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER

        val consumer = androidx.core.util.Consumer<Location?> { loc ->
            if (continuation.isActive) {
                if (loc != null) {
                    continuation.resume(loc)
                } else if (isGpsEnabled && isNetworkEnabled && preferredProvider == LocationManager.GPS_PROVIDER) {
                    try {
                        val networkSignal = androidx.core.os.CancellationSignal()
                        continuation.invokeOnCancellation { networkSignal.cancel() }
                        LocationManagerCompat.getCurrentLocation(
                            locationManager,
                            LocationManager.NETWORK_PROVIDER,
                            networkSignal,
                            ContextCompat.getMainExecutor(context),
                            androidx.core.util.Consumer { netLoc ->
                                if (continuation.isActive) {
                                    continuation.resume(netLoc)
                                }
                            }
                        )
                    } catch (e: SecurityException) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                } else {
                    continuation.resume(null)
                }
            }
        }

        try {
            LocationManagerCompat.getCurrentLocation(
                locationManager,
                preferredProvider,
                cancellationSignal,
                ContextCompat.getMainExecutor(context),
                consumer
            )
        } catch (e: SecurityException) {
            if (continuation.isActive) continuation.resume(null)
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(null)
        }
    }
}

/**
 * Service locator and provider for [CurrentLocationProvider].
 */
object DefaultCurrentLocationProvider {
    @Volatile
    private var provider: CurrentLocationProvider? = null

    fun initialize(context: Context): CurrentLocationProvider {
        return provider ?: synchronized(this) {
            provider ?: AndroidCurrentLocationProvider(context.applicationContext).also {
                provider = it
            }
        }
    }

    var instance: CurrentLocationProvider
        get() {
            val current = provider
            if (current != null) return current
            return synchronized(this) {
                provider ?: AndroidCurrentLocationProvider(null).also { provider = it }
            }
        }
        @VisibleForTesting
        set(value) {
            provider = value
        }

    fun isInitialized(): Boolean = provider != null
}
