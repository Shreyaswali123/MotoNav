package com.example.ble

import com.example.model.BleDiagnostics
import com.example.model.ConnectionState
import com.example.model.MotoNavDevice
import com.example.model.Route
import com.example.model.RouteTransferProgress
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface isolating Bluetooth Low Energy communication with the ESP32-S3 navigation device.
 * Real BLE stack and GATT protocols can replace this implementation without changing the UI.
 */
interface BleRepository {
    val connectionState: StateFlow<ConnectionState>
    val connectedDevice: StateFlow<MotoNavDevice?>
    val transferProgress: StateFlow<RouteTransferProgress>
    val lastError: StateFlow<String?>
    val diagnostics: StateFlow<BleDiagnostics>

    fun connect(deviceId: String = "MotoNav-01")
    fun disconnect()
    fun readStatus()
    fun sendTestRoute()
    fun transferSerializedRoute(binary: ByteArray, crc32: Long)
    fun cancelTransfer()
    fun resetError()
    fun simulateError()
}
