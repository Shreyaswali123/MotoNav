package com.example.model

enum class UnitSystem(val label: String) {
    KILOMETERS("Kilometers (km)"),
    MILES("Miles (mi)")
}

data class NavigationPreferences(
    val unitSystem: UnitSystem = UnitSystem.KILOMETERS
)

data class AboutMotoNavInfo(
    val appName: String = "MotoNav",
    val appVersion: String = "1.0.0 (Build 101)",
    val firmwareVersionPlaceholder: String = "v1.4.2-esp32s3",
    val hardwareTarget: String = "ESP32-S3 2.1\" Round IPS Display (BLE 5.0)",
    val description: String = "MotoNav is a motorcycle navigation companion app that lets riders plan scenic routes on phone and sync turn-by-turn maneuvers directly to their ESP32-S3 handlebar navigation computer."
)
