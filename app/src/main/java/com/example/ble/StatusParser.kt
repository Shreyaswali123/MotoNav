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

    private const val MAX_UINT16 = 65535

    fun parse(raw: String): ParsedStatus {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return errorStatus(trimmed, "Malformed status message: empty status string")
        }

        val parts = trimmed.split(",")
        if (parts.size != 2) {
            return errorStatus(
                trimmed,
                "Malformed status message: expected 2 comma-separated fields, got ${parts.size} in '$trimmed'"
            )
        }

        val statusVerb = parts[0].trim().uppercase()
        val payload = parts[1].trim()

        if (statusVerb == "ACK") {
            val seq = payload.toIntOrNull()
            if (seq == null || seq !in 0..MAX_UINT16) {
                return errorStatus(trimmed, "Invalid ACK sequence: '$payload'")
            }
            return ParsedStatus(
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

        val knownRatioVerbs = setOf("IDLE", "RECEIVING", "VERIFYING", "ROUTE_READY", "ERROR")
        if (statusVerb !in knownRatioVerbs) {
            return errorStatus(trimmed, "Unknown status message: '$trimmed'")
        }

        val slashParts = payload.split("/")
        if (slashParts.size != 2) {
            return errorStatus(trimmed, "Invalid packet ratio format: '$payload'")
        }

        val currentStr = slashParts[0].trim()
        val totalStr = slashParts[1].trim()
        if (currentStr.isEmpty() || totalStr.isEmpty()) {
            return errorStatus(trimmed, "Invalid packet ratio format: '$payload'")
        }

        val current = currentStr.toIntOrNull()
        val total = totalStr.toIntOrNull()
        if (current == null || current !in 0..MAX_UINT16 || total == null || total !in 0..MAX_UINT16) {
            return errorStatus(trimmed, "Invalid packet ratio numbers: '$payload'")
        }

        if (current > total) {
            return errorStatus(
                trimmed,
                "Invalid progress invariant: current packet ($current) cannot exceed total ($total)"
            )
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

            "RECEIVING" -> {
                if (total <= 0) {
                    return errorStatus(
                        trimmed,
                        "Invalid progress invariant: total packets must be greater than 0 for RECEIVING"
                    )
                }
                val percentage = ((current.toFloat() / total) * 100).toInt().coerceIn(0, 100)
                ParsedStatus(
                    state = ConnectionState.Transferring,
                    progress = RouteTransferProgress(
                        percentage = percentage,
                        currentPacket = current,
                        totalPackets = total,
                        stepDescription = "Receiving route packets ($current/$total)..."
                    ),
                    rawStatus = trimmed
                )
            }

            "VERIFYING" -> {
                if (total <= 0) {
                    return errorStatus(
                        trimmed,
                        "Invalid progress invariant: total packets must be greater than 0 for VERIFYING"
                    )
                }
                val percentage = ((current.toFloat() / total) * 100).toInt().coerceIn(0, 100)
                ParsedStatus(
                    state = ConnectionState.Transferring,
                    progress = RouteTransferProgress(
                        percentage = percentage,
                        currentPacket = current,
                        totalPackets = total,
                        stepDescription = "Verifying route on ESP32 ($current/$total)..."
                    ),
                    rawStatus = trimmed
                )
            }

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

            "ERROR" -> {
                val percentage = if (total > 0) {
                    ((current.toFloat() / total) * 100).toInt().coerceIn(0, 100)
                } else {
                    0
                }
                ParsedStatus(
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
            }

            else -> errorStatus(trimmed, "Unknown status message: '$trimmed'")
        }
    }

    private fun errorStatus(rawStatus: String, errorMessage: String): ParsedStatus {
        return ParsedStatus(
            state = ConnectionState.Error,
            progress = RouteTransferProgress(
                percentage = 0,
                currentPacket = 0,
                totalPackets = 0,
                stepDescription = "Error: $errorMessage"
            ),
            rawStatus = rawStatus,
            errorMessage = errorMessage
        )
    }
}
