package com.example.model

/**
 * Bluetooth Low Energy connection state for the ESP32-S3 MotoNav device.
 */
enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Transferring,
    RouteReady,
    Error
}

/**
 * Metadata representation of the connected MotoNav motorcycle device.
 * Real values populated from actual BLE scan and GATT characteristics.
 * Null indicates the metric is unavailable or not provided by the device.
 */
data class MotoNavDevice(
    val id: String = "MotoNav-01",
    val name: String = "MotoNav-01",
    val address: String? = null,
    val hardwareModel: String = "ESP32-S3 (Round HUD)",
    val firmwareVersion: String? = null,
    val batteryPercentage: Int? = null,
    val rssiDbm: Int? = null,
    val rawStatus: String? = null
)

/**
 * Progress tracking when sending waypoint and route data packets to the ESP32 device.
 */
data class RouteTransferProgress(
    val percentage: Int = 0,
    val currentPacket: Int = 0,
    val totalPackets: Int = 0,
    val stepDescription: String = "Idle"
)
