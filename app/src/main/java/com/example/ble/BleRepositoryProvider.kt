package com.example.ble

import android.content.Context
import androidx.annotation.VisibleForTesting

/**
 * Service locator and singleton provider for the active BleRepository.
 * Initialized with Application Context in MainActivity to provide the
 * real AndroidBleRepository stack to ViewModels across the application.
 */
object BleRepositoryProvider {

    @Volatile
    private var repository: BleRepository? = null

    /**
     * Initializes the real Android BLE repository with application context.
     */
    fun initialize(context: Context): BleRepository {
        return repository ?: synchronized(this) {
            repository ?: AndroidBleRepository(context.applicationContext).also {
                repository = it
            }
        }
    }

    /**
     * Current BLE repository instance. If not yet initialized (such as in local JVM tests),
     * returns a fallback mock instance to avoid breaking tests.
     */
    var instance: BleRepository
        get() {
            val current = repository
            if (current != null) return current
            return synchronized(this) {
                repository ?: MockBleRepository().also { repository = it }
            }
        }
        @VisibleForTesting
        set(value) {
            repository = value
        }

    fun isInitialized(): Boolean = repository != null
}
