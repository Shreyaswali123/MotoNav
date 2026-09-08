package com.example.ble

import com.example.model.ConnectionState
import com.example.model.RouteTransferProgress

data class ParsedStatus(
    val state: ConnectionState,
    val progress: RouteTransferProgress,
    val rawStatus: String,
    val errorMessage: String? = null
)

/**
 * Parser for MotoNav-01 ESP32-S3 status strings notified or read from the Status characteristic:
 * - IDLE,x/y
 * - RECEIVING,x/y
 * - VERIFYING,x/y
 * - ROUTE_READY,x/y
 * - ERROR,x/y
 */
object StatusParser {

    fun parse(raw: String): ParsedStatus {
        val trimmed = raw.trim()
        val parts = trimmed.split(",")
        val statusVerb = parts.getOrNull(0)?.trim()?.uppercase() ?: ""
        val packetRatio = parts.getOrNull(1)?.trim() ?: ""

        var current = 0
        var total = 0
        if (packetRatio.contains("/")) {
            val slashParts = packetRatio.split("/")
            current = slashParts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            total = slashParts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        }

        val percentage = if (total > 0) {
            ((current.toFloat() / total) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }

        return when (statusVerb) {
            "IDLE" -> ParsedStatus(
                state = ConnectionState.Connected,
                progress = RouteTransferProgress(
                    percentage = 0,
                    currentPacket = current,
                    totalPackets = total,
                    stepDescription = "Standby (Idle)"
                ),
                rawStatus = trimmed
            )

            "RECEIVING" -> ParsedStatus(
                state = ConnectionState.Transferring,
                progress = RouteTransferProgress(
                    percentage = percentage,
                    currentPacket = current,
                    totalPackets = total,
                    stepDescription = "Receiving route packets ($current/$total)..."
                ),
                rawStatus = trimmed
            )

            "VERIFYING" -> ParsedStatus(
                state = ConnectionState.Transferring,
                progress = RouteTransferProgress(
                    percentage = percentage,
                    currentPacket = current,
                    totalPackets = total,
                    stepDescription = "Verifying route on ESP32 ($current/$total)..."
                ),
                rawStatus = trimmed
            )

            "ROUTE_READY" -> ParsedStatus(
                state = ConnectionState.RouteReady,
                progress = RouteTransferProgress(
                    percentage = 100,
                    currentPacket = current,
                    totalPackets = total,
                    stepDescription = "Route synced to ESP32 cockpit HUD ($current/$total)"
                ),
                rawStatus = trimmed
            )

            "ACK" -> {
                val seq = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                ParsedStatus(
                    state = ConnectionState.Transferring,
                    progress = RouteTransferProgress(
                        percentage = 0,
                        currentPacket = seq,
                        totalPackets = 0,
                        stepDescription = "Packet $seq acknowledged"
                    ),
                    rawStatus = trimmed
                )
            }

            "ERROR" -> ParsedStatus(
                state = ConnectionState.Error,
                progress = RouteTransferProgress(
                    percentage = percentage,
                    currentPacket = current,
                    totalPackets = total,
                    stepDescription = "Device error at packet $current/$total"
                ),
                rawStatus = trimmed,
                errorMessage = "MotoNav-01 reported error ($trimmed)"
            )

            else -> ParsedStatus(
                state = ConnectionState.Connected,
                progress = RouteTransferProgress(
                    percentage = 0,
                    currentPacket = current,
                    totalPackets = total,
                    stepDescription = "Status: $trimmed"
                ),
                rawStatus = trimmed
            )
        }
    }
}
