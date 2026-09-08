package com.example.ble

import com.example.model.BleDiagnostics
import com.example.model.ConnectionState
import com.example.model.MotoNavDevice
import com.example.model.Route
import com.example.model.RouteTransferProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Placeholder implementation of BleRepository that simulates the ESP32-S3
 * connection lifecycle and packet transfer progress cleanly for the UI.
 */
class MockBleRepository(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : BleRepository {

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<MotoNavDevice?>(null)
    override val connectedDevice: StateFlow<MotoNavDevice?> = _connectedDevice.asStateFlow()

    private val _transferProgress = MutableStateFlow(RouteTransferProgress())
    override val transferProgress: StateFlow<RouteTransferProgress> = _transferProgress.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _diagnostics = MutableStateFlow(BleDiagnostics())
    override val diagnostics: StateFlow<BleDiagnostics> = _diagnostics.asStateFlow()

    private var connectionJob: Job? = null
    private var transferJob: Job? = null

    override fun connect(deviceId: String) {
        connectionJob?.cancel()
        _lastError.value = null
        _connectionState.value = ConnectionState.Connecting
        _diagnostics.value = BleDiagnostics(
            isScanning = true,
            disconnectReconnectState = "Scanning for $deviceId..."
        )
        
        connectionJob = scope.launch {
            delay(600)
            _diagnostics.value = _diagnostics.value.copy(
                isScanning = false,
                deviceDiscovered = true,
                discoveredDeviceName = deviceId,
                discoveredDeviceAddress = "24:6F:28:B1:0A:12",
                discoveredRssi = -64,
                isConnecting = true,
                disconnectReconnectState = "Connecting to GATT server..."
            )
            delay(600)
            _connectedDevice.value = MotoNavDevice(
                name = deviceId,
                address = "24:6F:28:B1:0A:12",
                rssiDbm = -64,
                rawStatus = "IDLE,0/0"
            )
            _connectionState.value = ConnectionState.Connected
            _diagnostics.value = _diagnostics.value.copy(
                isConnecting = false,
                isConnected = true,
                servicesDiscovered = true,
                serviceUuidFound = true,
                controlCharacteristicFound = true,
                statusCharacteristicFound = true,
                routeDataCharacteristicFound = true,
                statusNotificationsEnabled = true,
                initialStatusRead = true,
                lastStatusMessage = "IDLE,0/0",
                lastStatusReadTimestamp = System.currentTimeMillis(),
                disconnectReconnectState = "Connected (Ready for status/verification)"
            )
        }
    }

    override fun readStatus() {
        if (_connectionState.value == ConnectionState.Connected || _connectionState.value == ConnectionState.RouteReady) {
            val msg = _connectedDevice.value?.rawStatus ?: "IDLE,0/0"
            _diagnostics.value = _diagnostics.value.copy(
                lastStatusMessage = msg,
                lastStatusReadTimestamp = System.currentTimeMillis(),
                disconnectReconnectState = "Status read OK: '$msg'"
            )
        } else {
            val err = "Cannot read status: MotoNav-01 is disconnected"
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
        }
    }

    override fun sendTestRoute() {
        val binary = TestRouteFixture.createTestRouteBinary()
        val crc = TestRouteFixture.calculateCrc32(binary)
        transferSerializedRoute(binary, crc)
    }

    override fun transferSerializedRoute(binary: ByteArray, crc32: Long) {
        if (_connectionState.value != ConnectionState.Connected && _connectionState.value != ConnectionState.RouteReady) {
            val err = "Cannot send route: MotoNav-01 is not connected"
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
            return
        }

        if (!_diagnostics.value.statusNotificationsEnabled) {
            val err = "Cannot send route: Status notifications are not enabled"
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
            return
        }

        transferJob?.cancel()
        _lastError.value = null
        _connectionState.value = ConnectionState.Transferring

        val packets = TestRouteFixture.createRouteDataPackets(binary, chunkSize = 20)
        val totalPackets = packets.size
        val hexCrc = "0x${crc32.toString(16).uppercase()}"

        transferJob = scope.launch {
            _transferProgress.value = RouteTransferProgress(
                percentage = 0,
                currentPacket = 0,
                totalPackets = totalPackets,
                stepDescription = "START_ROUTE (${binary.size} bytes) sent to Control char..."
            )
            delay(150)

            for (packet in packets) {
                _transferProgress.value = RouteTransferProgress(
                    percentage = (packet.sequence * 100) / totalPackets,
                    currentPacket = packet.sequence,
                    totalPackets = totalPackets,
                    stepDescription = "Sending packet ${packet.sequence + 1}/$totalPackets (${packet.payloadLength} B)..."
                )
                delay(200)

                // Simulate ACK,<seq>
                val ackMsg = "ACK,${packet.sequence}"
                _diagnostics.value = _diagnostics.value.copy(
                    lastStatusMessage = ackMsg,
                    lastStatusReadTimestamp = System.currentTimeMillis()
                )

                val ackedCount = packet.sequence + 1
                val progressPercent = (ackedCount * 100) / totalPackets
                _transferProgress.value = RouteTransferProgress(
                    percentage = progressPercent,
                    currentPacket = ackedCount,
                    totalPackets = totalPackets,
                    stepDescription = "Packet $ackedCount/$totalPackets acknowledged ($ackMsg)"
                )
                delay(150)
            }

            _transferProgress.value = RouteTransferProgress(
                percentage = 100,
                currentPacket = totalPackets,
                totalPackets = totalPackets,
                stepDescription = "END_ROUTE (CRC $hexCrc) sent. Verifying route..."
            )
            delay(200)

            val finalStatus = "ROUTE_READY,${binary.size}/${binary.size}"
            _connectedDevice.value = (_connectedDevice.value ?: MotoNavDevice()).copy(rawStatus = finalStatus)
            _connectionState.value = ConnectionState.RouteReady
            _transferProgress.value = RouteTransferProgress(
                percentage = 100,
                currentPacket = totalPackets,
                totalPackets = totalPackets,
                stepDescription = "Route transfer verified: $finalStatus"
            )
            _diagnostics.value = _diagnostics.value.copy(
                lastStatusMessage = finalStatus,
                lastStatusReadTimestamp = System.currentTimeMillis(),
                disconnectReconnectState = "Route transfer verified ($finalStatus)"
            )
        }
    }

    override fun disconnect() {
        connectionJob?.cancel()
        transferJob?.cancel()
        _connectionState.value = ConnectionState.Disconnected
        _connectedDevice.value = null
        _transferProgress.value = RouteTransferProgress(0, 0, 0, "Disconnected")
        _diagnostics.value = _diagnostics.value.copy(
            isScanning = false,
            isConnecting = false,
            isConnected = false,
            servicesDiscovered = false,
            serviceUuidFound = false,
            controlCharacteristicFound = false,
            statusCharacteristicFound = false,
            routeDataCharacteristicFound = false,
            statusNotificationsEnabled = false,
            disconnectReconnectState = "Disconnected"
        )
    }

    override fun transferRoute(route: Route) {
        if (_connectionState.value != ConnectionState.Connected && _connectionState.value != ConnectionState.RouteReady) {
            // Auto-connect if needed, then transfer
            _connectionState.value = ConnectionState.Connecting
            scope.launch {
                delay(800)
                _connectedDevice.value = MotoNavDevice()
                _connectionState.value = ConnectionState.Connected
                startTransferSimulation(route)
            }
            return
        }
        startTransferSimulation(route)
    }

    private fun startTransferSimulation(route: Route) {
        transferJob?.cancel()
        _connectionState.value = ConnectionState.Transferring
        val totalSteps = (route.waypoints.size + route.maneuvers.size).coerceAtLeast(12)

        transferJob = scope.launch {
            for (step in 1..totalSteps) {
                val percent = ((step.toFloat() / totalSteps) * 100).toInt()
                val description = when {
                    percent < 25 -> "Syncing header & route metadata..."
                    percent < 60 -> "Uploading ${route.waypoints.size} waypoints to ESP32..."
                    percent < 90 -> "Writing ${route.maneuvers.size} turn maneuvers to flash..."
                    else -> "Validating checksum on MotoNav-01..."
                }
                _transferProgress.value = RouteTransferProgress(
                    percentage = percent,
                    currentPacket = step,
                    totalPackets = totalSteps,
                    stepDescription = description
                )
                delay(220)
            }
            _connectionState.value = ConnectionState.RouteReady
            _transferProgress.value = RouteTransferProgress(
                percentage = 100,
                currentPacket = totalSteps,
                totalPackets = totalSteps,
                stepDescription = "Route synced to ESP32 cockpit HUD"
            )
        }
    }

    override fun cancelTransfer() {
        transferJob?.cancel()
        if (_connectionState.value == ConnectionState.Transferring) {
            _connectionState.value = ConnectionState.Connected
            _transferProgress.value = RouteTransferProgress(0, 0, 0, "Transfer cancelled")
        }
    }

    override fun resetError() {
        _lastError.value = null
        if (_connectionState.value == ConnectionState.Error) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun simulateError() {
        transferJob?.cancel()
        connectionJob?.cancel()
        _lastError.value = "BLE GATT communication timeout with MotoNav-01 (0x13)"
        _connectionState.value = ConnectionState.Error
    }

    companion object {
        // Singleton holder for app lifecycle
        val instance: MockBleRepository by lazy { MockBleRepository() }
    }
}
