package com.example.ble

import java.util.UUID

/**
 * Bluetooth Low Energy GATT service and characteristic constants for the
 * ESP32-S3 MotoNav motorcycle navigation HUD.
 */
object BleConstants {
    const val TARGET_DEVICE_NAME = "MotoNav-01"
    const val CMD_CANCEL_ROUTE: Byte = 0x03

    // Custom MotoNav Service UUID
    val SERVICE_UUID: UUID = UUID.fromString("f0debc9a-7856-3412-5678-123412345678")

    // Custom MotoNav Characteristic UUIDs
    val CONTROL_CHAR_UUID: UUID = UUID.fromString("f1debc9a-7856-3412-5678-123412345678")
    val STATUS_CHAR_UUID: UUID = UUID.fromString("f2debc9a-7856-3412-5678-123412345678")
    val ROUTE_DATA_CHAR_UUID: UUID = UUID.fromString("f3debc9a-7856-3412-5678-123412345678")

    // Standard Client Characteristic Configuration Descriptor (CCCD) for notifications
    val CCCD_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
