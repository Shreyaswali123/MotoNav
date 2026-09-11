package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.model.BleDiagnostics
import com.example.model.ConnectionState
import com.example.model.MotoNavDevice
import com.example.model.RouteTransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Production Android Bluetooth Low Energy repository for the MotoNav ESP32-S3 device.
 *
 * Implements:
 * 1. BLE Scanning filtered for "MotoNav-01"
 * 2. GATT connection lifecycle & service discovery (Service: f0debc9a-7856-3412-5678-123412345678)
 * 3. Discovering Control (f1...), Status (f2...), and Route Data (f3...) characteristics
 * 4. Enabling GATT CCCD notifications on the Status characteristic
 * 5. Reading the Status characteristic after notifications are enabled
 * 6. Parsing status strings (IDLE,x/y, RECEIVING,x/y, VERIFYING,x/y, ROUTE_READY,x/y, ERROR,x/y)
 * 7. Real connection and RSSI telemetry without faking battery, firmware, or route-ready state
 * 8. Real-time diagnostic verification state tracking without faked values
 * 9. Clean disconnection and GATT resource reclamation
 */
class AndroidBleRepository(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) : BleRepository {

    companion object {
        private const val TAG = "MotoNavBle"
        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

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

    // GATT & Characteristic handles
    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var routeDataCharacteristic: BluetoothGattCharacteristic? = null

    private fun isActiveGatt(gatt: BluetoothGatt): Boolean = bluetoothGatt === gatt

    // Transfer synchronization & incoming status notifications
    private var transferJob: Job? = null
    private data class PendingWrite(
        val generation: Long,
        val deferred: CompletableDeferred<Int>
    )

    private var pendingWriteDeferred: PendingWrite? = null
    private val writeMutex = Mutex()
    private val incomingStatusFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val ackSynchronizer = AckSynchronizer(tag = TAG)
    private val routeReadyEventTracker = RouteReadyEventTracker()
    private val nextTransferGeneration = AtomicLong(0L)

    @Volatile
    private var activeTransferGeneration = 0L

    // Scanning state & timeout tracking
    private var isScanning = false
    private var scanTimeoutJob: Job? = null

    // BLE Scan Callback
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = try {
                device.name
            } catch (e: SecurityException) {
                null
            } ?: result.scanRecord?.deviceName

            Log.d(TAG, "BLE Scan found device: $deviceName (${device.address}), RSSI: ${result.rssi}")

            if (deviceName == BleConstants.TARGET_DEVICE_NAME || device.name == BleConstants.TARGET_DEVICE_NAME) {
                Log.i(TAG, "Matched target device '${BleConstants.TARGET_DEVICE_NAME}' at ${device.address}")
                onTargetDeviceDiscovered(device, result.rssi)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed with errorCode: $errorCode")
            stopScan()
            val errorText = "BLE scan failed with error code: $errorCode"
            _connectionState.value = ConnectionState.Error
            _lastError.value = errorText
            _diagnostics.value = _diagnostics.value.copy(
                isScanning = false,
                disconnectReconnectState = "Scan Failed ($errorCode)",
                lastError = errorText
            )
        }
    }

    // GATT Callback
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isActiveGatt(gatt)) return
            Log.d(TAG, "onConnectionStateChange status=$status, newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                val hexStatus = "0x" + Integer.toHexString(status)
                val errMsg = "GATT connection error (status $status / $hexStatus)"
                Log.e(TAG, errMsg)
                disconnectAndCleanup(errMsg)
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server on MotoNav-01. Initiating service discovery...")
                    _connectionState.value = ConnectionState.Connecting
                    _diagnostics.value = _diagnostics.value.copy(
                        isConnecting = false,
                        isConnected = true,
                        disconnectReconnectState = "Connected to GATT server. Discovering services...",
                        lastError = null
                    )

                    // Request real RSSI from hardware
                    try {
                        gatt.readRemoteRssi()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to request initial RSSI", e)
                    }

                    val discoveryStarted = gatt.discoverServices()
                    if (!discoveryStarted) {
                        disconnectAndCleanup("Failed to start GATT service discovery")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from MotoNav-01 GATT server")
                    disconnectAndCleanup(if (status != BluetoothGatt.GATT_SUCCESS) "GATT connection dropped (status $status)" else null)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isActiveGatt(gatt)) return
            Log.d(TAG, "onServicesDiscovered status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                val errMsg = "Service discovery failed with status $status"
                _diagnostics.value = _diagnostics.value.copy(
                    servicesDiscovered = false,
                    lastError = errMsg,
                    disconnectReconnectState = errMsg
                )
                disconnectAndCleanup(errMsg)
                return
            }

            val customService = gatt.getService(BleConstants.SERVICE_UUID)
            val serviceFound = customService != null

            if (customService == null) {
                val errMsg = "Custom service ${BleConstants.SERVICE_UUID} not found on MotoNav-01"
                Log.e(TAG, errMsg)
                _diagnostics.value = _diagnostics.value.copy(
                    servicesDiscovered = true,
                    serviceUuidFound = false,
                    controlCharacteristicFound = false,
                    statusCharacteristicFound = false,
                    routeDataCharacteristicFound = false,
                    lastError = errMsg,
                    disconnectReconnectState = "Custom service not found"
                )
                disconnectAndCleanup(errMsg)
                return
            }

            val control = customService.getCharacteristic(BleConstants.CONTROL_CHAR_UUID)
            val statusChar = customService.getCharacteristic(BleConstants.STATUS_CHAR_UUID)
            val routeData = customService.getCharacteristic(BleConstants.ROUTE_DATA_CHAR_UUID)

            val controlFound = control != null
            val statusFound = statusChar != null
            val routeDataFound = routeData != null

            _diagnostics.value = _diagnostics.value.copy(
                servicesDiscovered = true,
                serviceUuidFound = true,
                controlCharacteristicFound = controlFound,
                statusCharacteristicFound = statusFound,
                routeDataCharacteristicFound = routeDataFound,
                disconnectReconnectState = "Services & characteristics discovered. Enabling notifications..."
            )

            val missingList = mutableListOf<String>()
            if (!controlFound) missingList.add("Control (${BleConstants.CONTROL_CHAR_UUID})")
            if (!statusFound) missingList.add("Status (${BleConstants.STATUS_CHAR_UUID})")
            if (!routeDataFound) missingList.add("Route Data (${BleConstants.ROUTE_DATA_CHAR_UUID})")

            if (missingList.isNotEmpty()) {
                val errorMsg = "Missing characteristics: ${missingList.joinToString(", ")}"
                Log.e(TAG, errorMsg)
                _diagnostics.value = _diagnostics.value.copy(lastError = errorMsg)
                disconnectAndCleanup(errorMsg)
                return
            }

            controlCharacteristic = control
            statusCharacteristic = statusChar
            routeDataCharacteristic = routeData

            Log.i(TAG, "All three custom characteristics discovered successfully. Enabling notifications on Status...")
            enableStatusNotifications(gatt, statusChar!!)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (!isActiveGatt(gatt)) return
            Log.d(TAG, "onDescriptorWrite descriptor=${descriptor.uuid} status=$status")
            if (descriptor.uuid == BleConstants.CCCD_DESCRIPTOR_UUID) {
                val success = (status == BluetoothGatt.GATT_SUCCESS)
                if (success) {
                    Log.i(TAG, "Status characteristic notifications enabled. Reading initial Status...")
                } else {
                    val errorMessage = "CCCD descriptor write failed with status $status"
                    Log.e(TAG, errorMessage)
                    disconnectAndCleanup(errorMessage)
                    return
                }

                _diagnostics.value = _diagnostics.value.copy(
                    statusNotificationsEnabled = true,
                    disconnectReconnectState = "Notifications active. Reading initial status..."
                )

                // Read the Status characteristic
                statusCharacteristic?.let { sc ->
                    readStatusCharacteristic(gatt, sc)
                }

                if (_connectionState.value == ConnectionState.Connecting) {
                    _connectionState.value = ConnectionState.Connected
                }
            }
        }

        private fun handleIncomingStatusNotification(bytes: ByteArray) {
            val hexString = bytes.joinToString(" ") { "%02X".format(it) }
            val utf8String = String(bytes, Charsets.UTF_8).trim()
            Log.i(TAG, "[NOTIFICATION_CALLBACK] Status characteristic notification received (${bytes.size} bytes)")
            Log.i(TAG, "[NOTIFICATION_BYTES] raw hex=[$hexString], utf8='$utf8String'")
            Log.i(TAG, "[NOTIFICATION_PARSED] decoded string='$utf8String'")

            _diagnostics.value = _diagnostics.value.copy(
                initialStatusRead = true,
                lastStatusMessage = utf8String,
                lastStatusReadTimestamp = System.currentTimeMillis()
            )

            routeReadyEventTracker.onStatus(utf8String)

            // 1. Deliver to active ACK synchronizer
            ackSynchronizer.onNotificationReceived(utf8String)

            // 2. Also emit to shared flow for any UI observers
            incomingStatusFlow.tryEmit(utf8String)

            // 3. Update connection state and transfer progress models
            handleStatusString(utf8String)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (!isActiveGatt(gatt)) return
            if (characteristic.uuid == BleConstants.STATUS_CHAR_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "onCharacteristicRead Status success")
                    handleIncomingStatusNotification(value)
                } else {
                    val errMsg = "Read Status characteristic failed with status $status"
                    Log.e(TAG, errMsg)
                    _diagnostics.value = _diagnostics.value.copy(
                        lastError = errMsg,
                        disconnectReconnectState = "Status read failed (status $status)"
                    )
                }
            }
        }

        @Deprecated("Deprecated in Java")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (!isActiveGatt(gatt)) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (characteristic.uuid == BleConstants.STATUS_CHAR_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        @Suppress("DEPRECATION")
                        val bytes = characteristic.value ?: ByteArray(0)
                        Log.d(TAG, "onCharacteristicRead (legacy) Status success")
                        handleIncomingStatusNotification(bytes)
                    } else {
                        val errMsg = "Read Status characteristic failed with status $status"
                        Log.e(TAG, errMsg)
                        _diagnostics.value = _diagnostics.value.copy(
                            lastError = errMsg,
                            disconnectReconnectState = "Status read failed (status $status)"
                        )
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!isActiveGatt(gatt)) return
            if (characteristic.uuid == BleConstants.STATUS_CHAR_UUID) {
                handleIncomingStatusNotification(value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (!isActiveGatt(gatt)) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (characteristic.uuid == BleConstants.STATUS_CHAR_UUID) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value ?: ByteArray(0)
                    handleIncomingStatusNotification(bytes)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (!isActiveGatt(gatt)) return
            Log.d(TAG, "onCharacteristicWrite char=${characteristic.uuid} status=$status")
            val pendingWrite = pendingWriteDeferred
            if (pendingWrite != null && pendingWrite.generation == activeTransferGeneration) {
                pendingWrite.deferred.complete(status)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isActiveGatt(gatt)) return
            Log.i(TAG, "onMtuChanged: mtu=$mtu, status=$status")
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (!isActiveGatt(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onReadRemoteRssi: $rssi dBm")
                _connectedDevice.value = _connectedDevice.value?.copy(rssiDbm = rssi)
                _diagnostics.value = _diagnostics.value.copy(discoveredRssi = rssi)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(deviceId: String) {
        if (!BlePermissions.hasPermissions(context)) {
            val err = "Bluetooth permissions missing. Please grant Bluetooth permissions in settings."
            _lastError.value = err
            _connectionState.value = ConnectionState.Error
            _diagnostics.value = _diagnostics.value.copy(
                lastError = err,
                disconnectReconnectState = "Permission Denied"
            )
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            val err = "Bluetooth hardware unavailable on this device."
            _lastError.value = err
            _connectionState.value = ConnectionState.Error
            _diagnostics.value = _diagnostics.value.copy(
                lastError = err,
                disconnectReconnectState = "Hardware Unavailable"
            )
            return
        }

        if (!adapter.isEnabled) {
            val err = "Bluetooth is disabled. Please turn on Bluetooth."
            _lastError.value = err
            _connectionState.value = ConnectionState.Error
            _diagnostics.value = _diagnostics.value.copy(
                lastError = err,
                disconnectReconnectState = "Bluetooth Disabled"
            )
            return
        }

        // Clean up any existing connection first
        disconnect()

        _lastError.value = null
        _connectionState.value = ConnectionState.Connecting
        _diagnostics.value = BleDiagnostics(
            isScanning = true,
            disconnectReconnectState = "Scanning for ${BleConstants.TARGET_DEVICE_NAME}...",
            lastError = null
        )
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            val err = "BLE scanner is unavailable."
            _lastError.value = err
            _connectionState.value = ConnectionState.Error
            _diagnostics.value = _diagnostics.value.copy(
                isScanning = false,
                lastError = err,
                disconnectReconnectState = "Scanner Unavailable"
            )
            return
        }

        isScanning = true
        Log.i(TAG, "Starting BLE scan for '${BleConstants.TARGET_DEVICE_NAME}'...")

        val scanFilter = ScanFilter.Builder()
            .setDeviceName(BleConstants.TARGET_DEVICE_NAME)
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        } catch (e: Exception) {
            val err = "Failed to start BLE scan: ${e.message}"
            Log.e(TAG, err, e)
            _lastError.value = err
            _connectionState.value = ConnectionState.Error
            _diagnostics.value = _diagnostics.value.copy(
                isScanning = false,
                lastError = err,
                disconnectReconnectState = "Scan Start Error"
            )
            return
        }

        // Schedule timeout for discovery
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (isScanning && _connectionState.value == ConnectionState.Connecting) {
                val timeoutErr = "MotoNav-01 not found within 15s. Ensure the ESP32 is powered on and advertising."
                Log.w(TAG, timeoutErr)
                stopScan()
                _connectionState.value = ConnectionState.Disconnected
                _lastError.value = timeoutErr
                _diagnostics.value = _diagnostics.value.copy(
                    isScanning = false,
                    disconnectReconnectState = "Scan Timed Out (Device Not Discovered)",
                    lastError = timeoutErr
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return
        isScanning = false
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            Log.d(TAG, "BLE scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun onTargetDeviceDiscovered(device: BluetoothDevice, rssi: Int) {
        stopScan()

        // Create device representation without fake metrics
        _connectedDevice.value = MotoNavDevice(
            id = device.address,
            name = BleConstants.TARGET_DEVICE_NAME,
            address = device.address,
            hardwareModel = "ESP32-S3 (Round HUD)",
            firmwareVersion = null,      // Real: unavailable from custom GATT profile
            batteryPercentage = null,    // Real: unavailable from custom GATT profile
            rssiDbm = rssi,              // Real: observed RSSI from scan
            rawStatus = null
        )

        _diagnostics.value = _diagnostics.value.copy(
            isScanning = false,
            deviceDiscovered = true,
            discoveredDeviceName = BleConstants.TARGET_DEVICE_NAME,
            discoveredDeviceAddress = device.address,
            discoveredRssi = rssi,
            isConnecting = true,
            disconnectReconnectState = "Target Discovered (${device.address}, $rssi dBm). Connecting GATT..."
        )

        Log.i(TAG, "Connecting GATT to MotoNav-01 at ${device.address}...")
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableStatusNotifications(
        gatt: BluetoothGatt,
        statusChar: BluetoothGattCharacteristic
    ) {
        val notificationSet = gatt.setCharacteristicNotification(statusChar, true)
        if (!notificationSet) {
            val errorMessage = "Failed to enable Status characteristic notifications locally"
            Log.e(TAG, errorMessage)
            disconnectAndCleanup(errorMessage)
            return
        }

        val descriptor = statusChar.getDescriptor(BleConstants.CCCD_DESCRIPTOR_UUID)
        if (descriptor == null) {
            val errorMessage = "CCCD descriptor not found on Status characteristic"
            Log.e(TAG, errorMessage)
            disconnectAndCleanup(errorMessage)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val res = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (res != BluetoothStatusCodes.SUCCESS) {
                val errorMessage = "CCCD descriptor write returned status $res"
                Log.e(TAG, errorMessage)
                disconnectAndCleanup(errorMessage)
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            val writeSuccess = gatt.writeDescriptor(descriptor)
            if (!writeSuccess) {
                val errorMessage = "CCCD descriptor write could not be started"
                Log.e(TAG, errorMessage)
                disconnectAndCleanup(errorMessage)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readStatusCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        try {
            @Suppress("DEPRECATION")
            val success = gatt.readCharacteristic(characteristic)
            if (!success) {
                Log.w(TAG, "readCharacteristic returned false")
            }
        } catch (e: Exception) {
            val err = "Error executing readCharacteristic: ${e.message}"
            Log.e(TAG, err, e)
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
        }
    }

    @SuppressLint("MissingPermission")
    override fun readStatus() {
        val gatt = bluetoothGatt
        val statusChar = statusCharacteristic
        if (gatt == null || statusChar == null || !_diagnostics.value.isConnected) {
            val msg = "Cannot read status: MotoNav-01 is not connected or Status char not discovered"
            Log.w(TAG, msg)
            _lastError.value = msg
            _diagnostics.value = _diagnostics.value.copy(lastError = msg)
            return
        }

        Log.i(TAG, "Executing developer read on Status characteristic ${BleConstants.STATUS_CHAR_UUID}...")
        _diagnostics.value = _diagnostics.value.copy(
            disconnectReconnectState = "Reading Status characteristic..."
        )
        readStatusCharacteristic(gatt, statusChar)
    }

    private fun handleStatusString(raw: String) {
        val parsed = StatusParser.parse(raw)
        Log.i(TAG, "Parsed Status: state=${parsed.state}, progress=${parsed.progress.percentage}%, raw='${parsed.rawStatus}'")

        if (parsed.state == ConnectionState.Error) {
            _connectionState.value = ConnectionState.Error
            _lastError.value = parsed.errorMessage
            _diagnostics.value = _diagnostics.value.copy(lastError = parsed.errorMessage)
            return
        }

        if (parsed.state == ConnectionState.Connected &&
            _connectionState.value == ConnectionState.Connecting &&
            !_diagnostics.value.statusNotificationsEnabled
        ) {
            return
        }

        // If actively transferring, do not let an ACK or intermediate status overwrite transfer progress
        if (_connectionState.value == ConnectionState.Transferring) {
            _connectedDevice.value = (_connectedDevice.value ?: MotoNavDevice()).copy(
                rawStatus = parsed.rawStatus
            )
            return
        }

        _connectionState.value = parsed.state
        _transferProgress.value = parsed.progress
        _connectedDevice.value = (_connectedDevice.value ?: MotoNavDevice()).copy(
            rawStatus = parsed.rawStatus
        )

        if (parsed.errorMessage != null) {
            _lastError.value = parsed.errorMessage
            _diagnostics.value = _diagnostics.value.copy(lastError = parsed.errorMessage)
        }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        stopScan()
        val generation = activeTransferGeneration
        activeTransferGeneration = 0L
        transferJob?.cancel()
        ackSynchronizer.deactivate(generation.takeIf { it != 0L })
        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
                Log.d(TAG, "GATT disconnected and closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting GATT", e)
            }
        }
        bluetoothGatt = null
        controlCharacteristic = null
        statusCharacteristic = null
        routeDataCharacteristic = null

        _connectionState.value = ConnectionState.Disconnected
        _connectedDevice.value = null
        _transferProgress.value = RouteTransferProgress(0, 0, 0, "Disconnected")
        _diagnostics.value = _diagnostics.value.copy(
            isScanning = false,
            isConnecting = false,
            isConnected = false,
            disconnectReconnectState = "Disconnected",
            servicesDiscovered = false,
            serviceUuidFound = false,
            controlCharacteristicFound = false,
            statusCharacteristicFound = false,
            routeDataCharacteristicFound = false,
            statusNotificationsEnabled = false
        )
    }

    private fun disconnectAndCleanup(errorMessage: String?) {
        val generation = activeTransferGeneration
        activeTransferGeneration = 0L
        transferJob?.cancel()
        ackSynchronizer.deactivate(generation.takeIf { it != 0L })
        pendingWriteDeferred?.deferred?.completeExceptionally(CancellationException("GATT disconnected"))
        pendingWriteDeferred = null

        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT in cleanup", e)
        }
        bluetoothGatt = null
        controlCharacteristic = null
        statusCharacteristic = null
        routeDataCharacteristic = null

        if (errorMessage != null) {
            _lastError.value = errorMessage
            _connectionState.value = ConnectionState.Error
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
        _connectedDevice.value = null
        _transferProgress.value = RouteTransferProgress(0, 0, 0, errorMessage ?: "Disconnected")
        _diagnostics.value = _diagnostics.value.copy(
            isScanning = false,
            isConnecting = false,
            isConnected = false,
            disconnectReconnectState = errorMessage ?: "Disconnected",
            servicesDiscovered = false,
            serviceUuidFound = false,
            controlCharacteristicFound = false,
            statusCharacteristicFound = false,
            routeDataCharacteristicFound = false,
            statusNotificationsEnabled = false,
            lastError = errorMessage ?: _diagnostics.value.lastError
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeCharacteristicSuspend(
        generation: Long,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        charDescription: String = characteristic.uuid.toString()
    ): Boolean = writeMutex.withLock {
        if (activeTransferGeneration != generation) return@withLock false

        val deferred = CompletableDeferred<Int>()
        pendingWriteDeferred = PendingWrite(generation, deferred)

        val hasWriteResponse = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val writeType = if (hasWriteResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        try {
            Log.d(TAG, "[GATT_WRITE_START] Writing ${data.size} bytes to $charDescription (writeType: $writeType)")
            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val code = gatt.writeCharacteristic(characteristic, data, writeType)
                code == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }

            if (!initiated) {
                Log.e(TAG, "[GATT_WRITE_FAIL] writeCharacteristic returned false for $charDescription")
                if (pendingWriteDeferred?.generation == generation) {
                    pendingWriteDeferred = null
                }
                return@withLock false
            }

            val status = withTimeout(5000L) {
                deferred.await()
            }
            val success = (status == BluetoothGatt.GATT_SUCCESS)
            Log.d(TAG, "[GATT_WRITE_END] Write to $charDescription finished with status=$status (success=$success)")
            return@withLock success
        } catch (e: Exception) {
            Log.e(TAG, "[GATT_WRITE_ERROR] Exception during write to $charDescription: ${e.message}")
            return@withLock false
        } finally {
            if (pendingWriteDeferred?.generation == generation) {
                pendingWriteDeferred = null
            }
        }
    }

    override fun sendTestRoute() {
        val binary = TestRouteFixture.createTestRouteBinary()
        if (binary.size != TestRouteFixture.EXPECTED_SIZE) {
            val err = "Local validation failed: generated route binary size is ${binary.size} bytes (expected ${TestRouteFixture.EXPECTED_SIZE} bytes)"
            Log.e(TAG, err)
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
            return
        }

        val crc = TestRouteFixture.calculateCrc32(binary)
        if (crc != TestRouteFixture.EXPECTED_CRC) {
            val err = "Local validation failed: calculated CRC 0x${crc.toString(16).uppercase()} does not match expected 0xDC0213B4"
            Log.e(TAG, err)
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
            return
        }

        transferBinary(binary, crc, isTestRoute = true)
    }

    override fun transferSerializedRoute(binary: ByteArray, crc32: Long) {
        transferBinary(binary, crc32, isTestRoute = false)
    }

    private fun transferBinary(binary: ByteArray, crc32: Long, isTestRoute: Boolean = false) {
        val actionName = if (isTestRoute) "test route" else "route"
        // 2. Verify BLE is connected before starting
        val gatt = bluetoothGatt
        if (gatt == null || (_connectionState.value != ConnectionState.Connected && _connectionState.value != ConnectionState.RouteReady)) {
            val err = "Cannot send $actionName: MotoNav-01 is not connected"
            Log.e(TAG, err)
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
            return
        }

        // 3. Verify Status notifications remain enabled immediately before route transfer
        if (!_diagnostics.value.statusNotificationsEnabled) {
            val err = "Cannot send $actionName: Status notifications are not active on ${BleConstants.STATUS_CHAR_UUID}"
            Log.e(TAG, err)
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
            return
        }

        val control = controlCharacteristic
        val routeData = routeDataCharacteristic
        val statusChar = statusCharacteristic
        if (control == null || routeData == null || statusChar == null) {
            val err = "Cannot send $actionName: Missing GATT characteristics (Control: ${control != null}, RouteData: ${routeData != null}, Status: ${statusChar != null})"
            Log.e(TAG, err)
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
            return
        }

        val hexCrc = "0x${crc32.toString(16).uppercase()}"
        val transferGeneration = nextTransferGeneration.incrementAndGet()
        activeTransferGeneration = transferGeneration
        val previousTransferJob = transferJob
        previousTransferJob?.cancel()
        _lastError.value = null

        transferJob = scope.launch {
            try {
            previousTransferJob?.join()
            if (activeTransferGeneration != transferGeneration) return@launch

            val routeReadyGeneration = routeReadyEventTracker.beginTransfer()
                Log.i(TAG, "Starting MotoNav route transfer ($actionName, size: ${binary.size} bytes, CRC: $hexCrc)...")
                if (activeTransferGeneration != transferGeneration) return@launch
                _connectionState.value = ConnectionState.Transferring

                // 4. Split and frame ROUTE_DATA packets
                val packets = TestRouteFixture.createRouteDataPackets(binary, chunkSize = 20)
                val totalPackets = packets.size

                if (activeTransferGeneration != transferGeneration) return@launch
                _transferProgress.value = RouteTransferProgress(
                    percentage = 0,
                    currentPacket = 0,
                    totalPackets = totalPackets,
                    stepDescription = "Activating ACK notification listener..."
                )

                // CRITICAL ARCHITECTURAL REQUIREMENT:
                // 1. Status notifications enabled (verified)
                // 2. ACK notification collector/listener ACTIVE BEFORE START_ROUTE and ROUTE_DATA #0
                ackSynchronizer.activate(transferGeneration)
                Log.i(TAG, "[ACK_WAITER_CREATE] ACK notification listener is ACTIVE prior to START_ROUTE")

                // 3. Send START_ROUTE: 01 + uint32 LE size
                val startCmd = TestRouteFixture.buildStartRouteCommand(binary.size)
                val startOk = writeCharacteristicSuspend(transferGeneration, gatt, control, startCmd, "Control (START_ROUTE)")
                if (!startOk) {
                    ackSynchronizer.deactivate(transferGeneration)
                    handleTransferError(transferGeneration, "BLE write failure: unable to send START_ROUTE command to Control characteristic")
                    return@launch
                }
                Log.i(TAG, "START_ROUTE command successfully written to Control characteristic")

                // 5. Transfer each packet: write -> wait for matching ACK,<sequence>
                for (packet in packets) {
                    if (activeTransferGeneration != transferGeneration || _connectionState.value != ConnectionState.Transferring || bluetoothGatt == null) {
                        ackSynchronizer.deactivate(transferGeneration)
                        handleTransferError(transferGeneration, "Disconnect occurred during route transfer")
                        return@launch
                    }

                    Log.i(TAG, "[ROUTE_DATA_WRITE] Writing ROUTE_DATA packet ${packet.sequence} (${packet.payloadLength} B payload, ${packet.framedBytes.size} B framed)...")
                    val writeOk = writeCharacteristicSuspend(transferGeneration, gatt, routeData, packet.framedBytes, "RouteData (packet ${packet.sequence})")
                    if (!writeOk) {
                        ackSynchronizer.deactivate(transferGeneration)
                        handleTransferError(transferGeneration, "BLE write failure on Route Data characteristic for packet ${packet.sequence}")
                        return@launch
                    }
                    Log.i(TAG, "[ROUTE_DATA_WRITE] Packet ${packet.sequence} write finished. Waiting for ACK,${packet.sequence}...")

                    val ackResult = ackSynchronizer.waitForAck(expectedSequence = packet.sequence, timeoutMs = 5000L)
                    when (ackResult) {
                        is AckWaitResult.Success -> {
                            Log.i(TAG, "[ACK_WAITER_COMPLETE] Packet ${packet.sequence} acknowledged by MotoNav-01 (raw: '${ackResult.raw}')")
                            val ackedPackets = packet.sequence + 1
                            val percent = ((ackedPackets.toFloat() / totalPackets) * 100).toInt()
                            if (activeTransferGeneration != transferGeneration) return@launch
                            _transferProgress.value = RouteTransferProgress(
                                percentage = percent,
                                currentPacket = ackedPackets,
                                totalPackets = totalPackets,
                                stepDescription = "Packet $ackedPackets/$totalPackets acknowledged (${ackResult.raw})"
                            )
                        }
                        is AckWaitResult.Timeout -> {
                            ackSynchronizer.deactivate(transferGeneration)
                            handleTransferError(transferGeneration, "ACK timeout: MotoNav-01 did not acknowledge packet ${packet.sequence} within 5000ms")
                            return@launch
                        }
                        is AckWaitResult.Error -> {
                            ackSynchronizer.deactivate(transferGeneration)
                            handleTransferError(transferGeneration, "Device error during route transfer: '${ackResult.message}'")
                            return@launch
                        }
                        is AckWaitResult.SequenceMismatch -> {
                            ackSynchronizer.deactivate(transferGeneration)
                            handleTransferError(transferGeneration, "Wrong ACK sequence received (expected 'ACK,${packet.sequence}', got '${ackResult.raw}')")
                            return@launch
                        }
                    }
                }

                // Deactivate ACK synchronizer after all packets are acknowledged
                ackSynchronizer.deactivate(transferGeneration)

                // 6. After all bytes are acknowledged, send END_ROUTE: 02 + uint32 LE CRC
                if (activeTransferGeneration != transferGeneration) return@launch
                _transferProgress.value = RouteTransferProgress(
                    percentage = 100,
                    currentPacket = totalPackets,
                    totalPackets = totalPackets,
                    stepDescription = "Sending END_ROUTE (CRC $hexCrc)..."
                )
                Log.i(TAG, "All $totalPackets packets acknowledged. Sending END_ROUTE (CRC: $hexCrc)...")
                routeReadyEventTracker.beginVerification(routeReadyGeneration)
                val endCmd = TestRouteFixture.buildEndRouteCommand(crc32)
                val endOk = writeCharacteristicSuspend(transferGeneration, gatt, control, endCmd, "Control (END_ROUTE)")
                if (!endOk) {
                    handleTransferError(transferGeneration, "BLE write failure: unable to send END_ROUTE command to Control characteristic")
                    return@launch
                }

                // 7. Read/poll Status and require ROUTE_READY
                Log.i(TAG, "END_ROUTE written. Verifying route status 'ROUTE_READY'...")
                if (activeTransferGeneration != transferGeneration) return@launch
                _transferProgress.value = RouteTransferProgress(
                    percentage = 100,
                    currentPacket = totalPackets,
                    totalPackets = totalPackets,
                    stepDescription = "Verifying route CRC on MotoNav-01..."
                )

                readStatusCharacteristic(gatt, statusChar)
                val verificationEvent = routeReadyEventTracker.awaitVerificationEvent(
                    generation = routeReadyGeneration,
                    timeoutMs = 6000L
                )
                if (verificationEvent is RouteVerificationEvent.Error) {
                    handleTransferError(transferGeneration, "MotoNav-01 reported error during route verification: '${verificationEvent.message}'")
                    return@launch
                }
                if (verificationEvent !is RouteVerificationEvent.Ready) {
                    handleTransferError(transferGeneration, "Route verification failed: expected 'ROUTE_READY' but received '${_diagnostics.value.lastStatusMessage ?: "timeout"}'")
                    return@launch
                }

                // 8. Display transfer progress and final result
                if (activeTransferGeneration != transferGeneration) return@launch
                val readyStatus = _diagnostics.value.lastStatusMessage ?: "ROUTE_READY,${binary.size}/${binary.size}"
                Log.i(TAG, "Route transfer PASSED! Status: $readyStatus")
                _connectionState.value = ConnectionState.RouteReady
                _connectedDevice.value = (_connectedDevice.value ?: MotoNavDevice()).copy(rawStatus = readyStatus)
                _transferProgress.value = RouteTransferProgress(
                    percentage = 100,
                    currentPacket = totalPackets,
                    totalPackets = totalPackets,
                    stepDescription = "Route transfer verified: $readyStatus"
                )
                _diagnostics.value = _diagnostics.value.copy(
                    lastStatusMessage = readyStatus,
                    lastStatusReadTimestamp = System.currentTimeMillis(),
                    disconnectReconnectState = if (isTestRoute) "Test route verified ($readyStatus)" else "Route transfer verified ($readyStatus)"
                )
            } catch (e: CancellationException) {
                Log.i(TAG, "Route transfer job cancelled")
            } catch (e: Exception) {
                val err = "Unexpected error during route transfer: ${e.message}"
                Log.e(TAG, err, e)
                handleTransferError(transferGeneration, err)
            } finally {
                ackSynchronizer.deactivate(transferGeneration)
            }
        }
    }

    private fun handleTransferError(generation: Long, errorMessage: String) {
        if (activeTransferGeneration != generation) return
        Log.e(TAG, "Route transfer error: $errorMessage")
        _lastError.value = errorMessage
        _connectionState.value = ConnectionState.Error
        _transferProgress.value = RouteTransferProgress(
            percentage = _transferProgress.value.percentage,
            currentPacket = _transferProgress.value.currentPacket,
            totalPackets = _transferProgress.value.totalPackets,
            stepDescription = "Failed: $errorMessage"
        )
        _diagnostics.value = _diagnostics.value.copy(
            lastError = errorMessage,
            disconnectReconnectState = "Transfer Error: $errorMessage"
        )
    }

    override fun cancelTransfer() {
        val generation = activeTransferGeneration
        if (generation == 0L || _connectionState.value != ConnectionState.Transferring) return

        val gatt = bluetoothGatt
        val control = controlCharacteristic
        scope.launch {
            val cancelSucceeded = if (
                activeTransferGeneration == generation &&
                gatt != null &&
                control != null
            ) {
                writeCharacteristicSuspend(
                    generation = generation,
                    gatt = gatt,
                    characteristic = control,
                    data = byteArrayOf(BleConstants.CMD_CANCEL_ROUTE),
                    charDescription = "Control (CANCEL_ROUTE)"
                )
            } else {
                false
            }

            if (activeTransferGeneration != generation) return@launch

            activeTransferGeneration = 0L
            transferJob?.cancel()
            ackSynchronizer.deactivate(generation)

            if (cancelSucceeded) {
                _connectionState.value = ConnectionState.Connected
                _transferProgress.value = RouteTransferProgress(0, 0, 0, "Transfer cancelled")
            } else {
                val errorMessage = "Failed to send CANCEL_ROUTE to MotoNav-01"
                _lastError.value = errorMessage
                _connectionState.value = ConnectionState.Error
                _transferProgress.value = RouteTransferProgress(
                    percentage = _transferProgress.value.percentage,
                    currentPacket = _transferProgress.value.currentPacket,
                    totalPackets = _transferProgress.value.totalPackets,
                    stepDescription = "Cancellation failed: $errorMessage"
                )
                _diagnostics.value = _diagnostics.value.copy(
                    lastError = errorMessage,
                    disconnectReconnectState = "Transfer cancellation failed"
                )
            }
        }
    }

    override fun resetError() {
        _lastError.value = null
        _diagnostics.value = _diagnostics.value.copy(lastError = null)
        if (_connectionState.value == ConnectionState.Error) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun simulateError() {
        disconnectAndCleanup("BLE GATT communication fault simulated")
    }
}
