package com.example.model

/**
 * Detailed real-time diagnostics tracking the complete BLE connection
 * verification lifecycle for MotoNav-01 (ESP32-S3).
 *
 * Tracks truthful hardware states without fake telemetry:
 * - BLE scanning
 * - Device discovered (name, address, RSSI)
 * - Connecting
 * - Connected
 * - GATT services discovered
 * - Control characteristic found
 * - Status characteristic found
 * - Route Data characteristic found
 * - Status notifications enabled
 * - Initial Status read
 * - Last Status message
 * - Disconnect/reconnect state
 * - Last BLE/GATT error
 */
data class BleDiagnostics(
    val isScanning: Boolean = false,
    val deviceDiscovered: Boolean = false,
    val discoveredDeviceName: String? = null,
    val discoveredDeviceAddress: String? = null,
    val discoveredRssi: Int? = null,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val servicesDiscovered: Boolean = false,
    val serviceUuidFound: Boolean = false,
    val controlCharacteristicFound: Boolean = false,
    val statusCharacteristicFound: Boolean = false,
    val routeDataCharacteristicFound: Boolean = false,
    val statusNotificationsEnabled: Boolean = false,
    val initialStatusRead: Boolean = false,
    val lastStatusMessage: String? = null,
    val lastStatusReadTimestamp: Long? = null,
    val disconnectReconnectState: String = "Disconnected",
    val lastError: String? = null
)
