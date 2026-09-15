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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.ArrayDeque
import java.util.UUID

internal enum class GattOperationKind {
    MTU,
    RSSI,
    SERVICE_DISCOVERY,
    DESCRIPTOR_WRITE,
    CHARACTERISTIC_READ,
    CHARACTERISTIC_WRITE
}

internal data class GattOperationToken(
    val operationId: Long,
    val epoch: Long,
    val gattIdentity: Any,
    val kind: GattOperationKind,
    val targetIdentity: Any?,
    val targetUuid: UUID?,
    val transferGeneration: Long?
)

internal data class GattOperationResult(
    val status: Int,
    val mtu: Int? = null,
    val rssi: Int? = null,
    val value: ByteArray? = null
)

internal class GattOperationStartException(message: String) : Exception(message)
internal class GattOperationTimeoutException(message: String) : Exception(message)
internal class GattOperationInvalidatedException(message: String) : Exception(message)

/**
 * Serializes Android's callback-based BluetoothGatt operations without holding
 * a lock while waiting for a callback. A queue instance belongs to one GATT
 * object and one connection epoch only.
 */
internal class GattOperationQueue(
    internal val gattIdentity: Any,
    internal val epoch: Long,
    private val scope: CoroutineScope,
    private val onTimeout: (GattOperationToken) -> Unit,
    private val onCancellation: (GattOperationToken) -> Unit = {}
) {
    private data class Request(
        val token: GattOperationToken,
        val timeoutMs: Long,
        val completion: CompletableDeferred<GattOperationResult>,
        val start: (GattOperationToken) -> Boolean
    )

    private data class Active(
        val request: Request,
        var timeoutJob: Job? = null
    )

    private val lock = Any()
    private val pending = ArrayDeque<Request>()
    private var active: Active? = null
    private var closed = false
    private var nextOperationId = 0L

    suspend fun execute(
        kind: GattOperationKind,
        targetIdentity: Any? = null,
        targetUuid: UUID? = null,
        transferGeneration: Long? = null,
        timeoutMs: Long = 5_000L,
        start: (GattOperationToken) -> Boolean
    ): GattOperationResult {
        val completion = CompletableDeferred<GattOperationResult>()
        val request: Request
        var startNow = false

        synchronized(lock) {
            if (closed) {
                throw GattOperationInvalidatedException("GATT operation queue is closed")
            }

            val token = GattOperationToken(
                operationId = ++nextOperationId,
                epoch = epoch,
                gattIdentity = gattIdentity,
                kind = kind,
                targetIdentity = targetIdentity,
                targetUuid = targetUuid,
                transferGeneration = transferGeneration
            )
            request = Request(token, timeoutMs, completion, start)
            pending.addLast(request)
            if (active == null) {
                active = Active(pending.removeFirst())
                startNow = true
            }
        }

        if (startNow) {
            scheduleStart(activeRequest())
        }

        return try {
            completion.await()
        } catch (e: CancellationException) {
            val activeRequest = synchronized(lock) {
                active?.request?.token == request.token
            }
            if (activeRequest) {
                invalidate(e)
                onCancellation(request.token)
            }
            throw e
        }
    }

    fun matches(
        kind: GattOperationKind,
        targetIdentity: Any? = null,
        targetUuid: UUID? = null
    ): Boolean = synchronized(lock) {
        val current = active?.request?.token ?: return@synchronized false
        current.kind == kind &&
            current.targetIdentity === targetIdentity &&
            current.targetUuid == targetUuid &&
            !closed
    }

    fun complete(
        kind: GattOperationKind,
        targetIdentity: Any? = null,
        targetUuid: UUID? = null,
        result: GattOperationResult,
        callbackGattIdentity: Any = gattIdentity
    ): Boolean {
        val token = synchronized(lock) {
            active?.request?.token?.takeIf {
                it.gattIdentity === callbackGattIdentity &&
                it.kind == kind &&
                    it.targetIdentity === targetIdentity &&
                    it.targetUuid == targetUuid &&
                    !closed
            }
        } ?: return false

        finish(token, result, null)
        return true
    }

    fun invalidate(cause: Throwable = GattOperationInvalidatedException("GATT operation queue invalidated")) {
        val requests = synchronized(lock) {
            if (closed) return
            closed = true
            val invalidated = ArrayList<Request>(pending.size + 1)
            active?.let { invalidated.add(it.request) }
            invalidated.addAll(pending)
            active?.timeoutJob?.cancel()
            active = null
            pending.clear()
            invalidated
        }
        requests.forEach { it.completion.completeExceptionally(cause) }
    }

    private fun activeRequest(): Request? = synchronized(lock) { active?.request }

    private fun scheduleStart(request: Request?) {
        if (request == null) return
        scope.launch { start(request) }
    }

    private fun start(request: Request) {
        val canStart = synchronized(lock) {
            !closed && active?.request?.token == request.token
        }

        if (!canStart) return

        val initiated = try {
            request.start(request.token)
        } catch (_: Throwable) {
            false
        }

        when (initiated) {
            false -> finish(
                request.token,
                null,
                GattOperationStartException("Failed to start ${request.token.kind} operation")
            )
            true -> {
                val timeoutJob = scope.launch {
                    kotlinx.coroutines.delay(request.timeoutMs)
                    timeout(request.token)
                }
                synchronized(lock) {
                    if (active?.request?.token == request.token && !closed) {
                        active?.timeoutJob = timeoutJob
                    } else {
                        timeoutJob.cancel()
                    }
                }
            }
        }
    }

    private fun finish(
        token: GattOperationToken,
        result: GattOperationResult?,
        failure: Throwable?
    ) {
        var completed: Request? = null
        var next: Request? = null

        synchronized(lock) {
            val current = active ?: return
            if (current.request.token != token || closed) return
            current.timeoutJob?.cancel()
            active = null
            completed = current.request
            if (pending.isNotEmpty()) {
                next = pending.removeFirst()
                active = Active(next!!)
            }
        }

        completed?.let {
            if (failure != null) it.completion.completeExceptionally(failure)
            else it.completion.complete(result ?: GattOperationResult(status = -1))
        }
        scheduleStart(next)
    }

    private fun timeout(token: GattOperationToken) {
        val requests = synchronized(lock) {
            val current = active ?: return
            if (current.request.token != token || closed) return
            closed = true
            current.timeoutJob?.cancel()
            val timedOut = ArrayList<Request>(pending.size + 1)
            timedOut.add(current.request)
            timedOut.addAll(pending)
            active = null
            pending.clear()
            timedOut
        }

        requests.firstOrNull()?.completion?.completeExceptionally(
            GattOperationTimeoutException("Timed out waiting for ${token.kind} callback")
        )
        requests.drop(1).forEach {
            it.completion.completeExceptionally(
                GattOperationInvalidatedException("GATT operation queue closed after timeout")
            )
        }
        onTimeout(token)
    }

}

private fun Boolean?.orFalse(): Boolean = this == true

/** Owns scan-attempt identity independently from the GATT connection epoch. */
internal class ScanAttemptCoordinator {
    private val lock = Any()
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null

    fun begin(): Long = synchronized(lock) {
        val generation = ++nextGeneration
        activeGeneration = generation
        generation
    }

    fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        activeGeneration == generation
    }

    fun claim(generation: Long): Boolean = synchronized(lock) {
        if (activeGeneration != generation) return@synchronized false
        activeGeneration = null
        true
    }

    fun invalidate(generation: Long? = null) = synchronized(lock) {
        if (generation == null || activeGeneration == generation) {
            activeGeneration = null
        }
    }

    fun currentGeneration(): Long? = synchronized(lock) { activeGeneration }
}

/** Filters transfer-specific status messages after a local cancellation boundary. */
internal class StatusNotificationGate {
    private var postCancellationBoundary = false

    fun markTransferStarted() {
        postCancellationBoundary = false
    }

    fun markTransferCancelled() {
        postCancellationBoundary = true
    }

    fun resetConnection() {
        postCancellationBoundary = false
    }

    fun shouldForward(rawStatus: String): Boolean {
        if (!postCancellationBoundary) return true
        val verb = rawStatus.trim().substringBefore(',').uppercase()
        return verb == "IDLE"
    }

    fun isPostCancellationBoundaryActive(): Boolean = postCancellationBoundary
}

/** Owns the local terminal boundary for the currently active transfer generation. */
internal class TransferTerminalBoundary {
    private var activeGeneration: Long? = null
    private var terminal = false

    fun begin(generation: Long) {
        activeGeneration = generation
        terminal = false
    }

    fun terminate(generation: Long): Boolean {
        if (activeGeneration != generation) return false
        activeGeneration = null
        terminal = true
        return true
    }

    fun reset() {
        activeGeneration = null
        terminal = false
    }

    fun shouldForward(rawStatus: String): Boolean {
        if (!terminal) return true
        return rawStatus.trim().substringBefore(',').uppercase() == "IDLE"
    }

    fun isTerminal(): Boolean = terminal

    fun currentGeneration(): Long? = activeGeneration
}

internal object BleCharacteristicPropertyPolicy {
    fun supportsResponseWrite(properties: Int): Boolean =
        properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0

    fun supportsStatusUpdates(properties: Int): Boolean =
        properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    fun cccdEnableValue(properties: Int): ByteArray? = when {
        properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else -> null
    }
}

internal sealed interface BondAction {
    object StartGattSetup : BondAction
    object InitiateCreateBond : BondAction
    object WaitForBonding : BondAction
    data class PairingFailed(val message: String) : BondAction
    object None : BondAction
}

/** Coordinates LE Security and bonding state transitions across connection epochs. */
internal class BleBondCoordinator {
    private val lock = Any()
    private var activeEpoch: Long? = null
    private var activeDeviceAddress: String? = null
    private var pairingInProgress: Boolean = false
    private var gattSetupStarted: Boolean = false

    fun begin(epoch: Long, deviceAddress: String?) = synchronized(lock) {
        activeEpoch = epoch
        activeDeviceAddress = deviceAddress
        pairingInProgress = false
        gattSetupStarted = false
    }

    fun onConnected(epoch: Long, deviceAddress: String?, bondState: Int): BondAction = synchronized(lock) {
        if (activeEpoch != epoch || activeDeviceAddress != deviceAddress) return@synchronized BondAction.None
        when (bondState) {
            BluetoothDevice.BOND_BONDED -> {
                if (!gattSetupStarted) {
                    gattSetupStarted = true
                    BondAction.StartGattSetup
                } else {
                    BondAction.None
                }
            }
            BluetoothDevice.BOND_BONDING -> {
                pairingInProgress = true
                BondAction.WaitForBonding
            }
            BluetoothDevice.BOND_NONE -> {
                pairingInProgress = true
                BondAction.InitiateCreateBond
            }
            else -> BondAction.None
        }
    }

    fun onBondStateChanged(
        epoch: Long,
        deviceAddress: String?,
        bondState: Int,
        prevBondState: Int
    ): BondAction = synchronized(lock) {
        if (activeEpoch != epoch || activeDeviceAddress != deviceAddress) return@synchronized BondAction.None

        when (bondState) {
            BluetoothDevice.BOND_BONDING -> {
                pairingInProgress = true
                BondAction.WaitForBonding
            }
            BluetoothDevice.BOND_BONDED -> {
                pairingInProgress = false
                if (!gattSetupStarted) {
                    gattSetupStarted = true
                    BondAction.StartGattSetup
                } else {
                    BondAction.None
                }
            }
            BluetoothDevice.BOND_NONE -> {
                if (pairingInProgress || prevBondState == BluetoothDevice.BOND_BONDING) {
                    pairingInProgress = false
                    BondAction.PairingFailed("Pairing with MotoNav-01 was cancelled or failed")
                } else {
                    BondAction.None
                }
            }
            else -> BondAction.None
        }
    }

    fun isBonded(bondState: Int?): Boolean = bondState == BluetoothDevice.BOND_BONDED

    fun isPairingInProgress(): Boolean = synchronized(lock) { pairingInProgress }

    fun isGattSetupStarted(): Boolean = synchronized(lock) { gattSetupStarted }

    fun activeEpoch(): Long? = synchronized(lock) { activeEpoch }

    fun activeDeviceAddress(): String? = synchronized(lock) { activeDeviceAddress }

    fun reset() = synchronized(lock) {
        activeEpoch = null
        activeDeviceAddress = null
        pairingInProgress = false
        gattSetupStarted = false
    }
}

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
        internal const val REQUESTED_ROUTE_ATT_MTU = 33
        internal const val ROUTE_DATA_VALUE_SIZE = 30

        internal fun isRouteDataMtuSufficient(attMtu: Int?): Boolean =
            attMtu != null && attMtu - 3 >= ROUTE_DATA_VALUE_SIZE
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val nextConnectionEpoch = AtomicLong(0L)

    @Volatile
    private var connectionEpoch = 0L

    @Volatile
    private var gattOperationQueue: GattOperationQueue? = null

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
    @Volatile
    private var negotiatedAttMtu: Int? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var routeDataCharacteristic: BluetoothGattCharacteristic? = null

    private fun isActiveGatt(gatt: BluetoothGatt): Boolean = bluetoothGatt === gatt

    // Transfer synchronization & incoming status notifications
    private var transferJob: Job? = null
    private val incomingStatusFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val ackSynchronizer = AckSynchronizer(tag = TAG)
    private val routeReadyEventTracker = RouteReadyEventTracker()
    private val statusNotificationGate = StatusNotificationGate()
    private val transferTerminalBoundary = TransferTerminalBoundary()
    private val nextTransferGeneration = AtomicLong(0L)

    @Volatile
    private var activeTransferGeneration = 0L

    @Volatile
    private var cancellingTransferGeneration: Long? = null

    private fun isTransferCancelling(generation: Long): Boolean =
        cancellingTransferGeneration == generation

    // Scanning state & timeout tracking
    private var isScanning = false
    private var scanTimeoutJob: Job? = null
    private val scanAttempts = ScanAttemptCoordinator()
    private var activeScanGeneration: Long? = null
    private var activeScanCallback: ScanCallback? = null

    // BLE Security & Bonding
    private val bondCoordinator = BleBondCoordinator()
    private var bondStateReceiver: BroadcastReceiver? = null
    private var isBondReceiverRegistered = false

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
                    Log.i(TAG, "Connected to GATT server on MotoNav-01. Checking bond state...")
                    negotiatedAttMtu = null
                    _connectionState.value = ConnectionState.Connecting
                    _diagnostics.value = _diagnostics.value.copy(
                        isConnecting = false,
                        isConnected = true,
                        lastError = null
                    )
                    handleConnectedBondState(gatt, connectionEpoch)
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

            if (!gattOperationQueue?.complete(
                    kind = GattOperationKind.SERVICE_DISCOVERY,
                    result = GattOperationResult(status = status),
                    callbackGattIdentity = gatt
                ).orFalse()
            ) return

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

            val unsupportedCapabilities = mutableListOf<String>()
            if (!BleCharacteristicPropertyPolicy.supportsResponseWrite(control!!.properties)) {
                unsupportedCapabilities.add("Control characteristic does not support WRITE")
            }
            if (!BleCharacteristicPropertyPolicy.supportsResponseWrite(routeData!!.properties)) {
                unsupportedCapabilities.add("Route Data characteristic does not support WRITE")
            }
            if (!BleCharacteristicPropertyPolicy.supportsStatusUpdates(statusChar!!.properties)) {
                unsupportedCapabilities.add("Status characteristic supports neither NOTIFY nor INDICATE")
            }
            if (unsupportedCapabilities.isNotEmpty()) {
                val errorMsg = "Unsupported MotoNav characteristic capabilities: ${unsupportedCapabilities.joinToString("; ")}"
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
            if (!gattOperationQueue?.complete(
                    kind = GattOperationKind.DESCRIPTOR_WRITE,
                    targetIdentity = descriptor,
                    targetUuid = descriptor.uuid,
                    result = GattOperationResult(status = status),
                    callbackGattIdentity = gatt
                ).orFalse()
            ) return
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

                if (_connectionState.value == ConnectionState.Connecting) {
                    _connectionState.value = ConnectionState.Connected
                }

                // Read the Status characteristic through the same FIFO GATT queue.
                statusCharacteristic?.let { sc ->
                    queueStatusRead(gatt, sc, "initial status")
                }
                queueRssiRead(gatt)
            }
        }

        private fun handleIncomingStatusNotification(bytes: ByteArray) {
            val hexString = bytes.joinToString(" ") { "%02X".format(it) }
            val utf8String = String(bytes, Charsets.UTF_8).trim()
            Log.i(TAG, "[NOTIFICATION_CALLBACK] Status characteristic notification received (${bytes.size} bytes)")
            Log.i(TAG, "[NOTIFICATION_BYTES] raw hex=[$hexString], utf8='$utf8String'")
            Log.i(TAG, "[NOTIFICATION_PARSED] decoded string='$utf8String'")

            if (!statusNotificationGate.shouldForward(utf8String)) {
                Log.i(TAG, "[NOTIFICATION_IGNORED] Suppressed post-cancellation transfer status '$utf8String'")
                return
            }
            if (!transferTerminalBoundary.shouldForward(utf8String)) {
                Log.i(TAG, "[NOTIFICATION_IGNORED] Suppressed post-terminal transfer status '$utf8String'")
                return
            }

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
            if (!gattOperationQueue?.complete(
                    kind = GattOperationKind.CHARACTERISTIC_READ,
                    targetIdentity = characteristic,
                    targetUuid = characteristic.uuid,
                    result = GattOperationResult(status = status, value = value),
                    callbackGattIdentity = gatt
                ).orFalse()
            ) return
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
                        if (gattOperationQueue?.complete(
                                kind = GattOperationKind.CHARACTERISTIC_READ,
                                targetIdentity = characteristic,
                                targetUuid = characteristic.uuid,
                                result = GattOperationResult(status = status, value = bytes),
                                callbackGattIdentity = gatt
                            ) == true
                        ) {
                            handleIncomingStatusNotification(bytes)
                        }
                    } else {
                        gattOperationQueue?.complete(
                            kind = GattOperationKind.CHARACTERISTIC_READ,
                            targetIdentity = characteristic,
                            targetUuid = characteristic.uuid,
                            result = GattOperationResult(status = status),
                            callbackGattIdentity = gatt
                        )
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
            gattOperationQueue?.complete(
                kind = GattOperationKind.CHARACTERISTIC_WRITE,
                targetIdentity = characteristic,
                targetUuid = characteristic.uuid,
                result = GattOperationResult(status = status),
                callbackGattIdentity = gatt
            )
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isActiveGatt(gatt)) return
            if (!gattOperationQueue?.complete(
                    kind = GattOperationKind.MTU,
                    result = GattOperationResult(status = status, mtu = mtu),
                    callbackGattIdentity = gatt
                ).orFalse()
            ) return
            negotiatedAttMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else null
            Log.i(TAG, "onMtuChanged: mtu=$mtu, status=$status")
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (!isActiveGatt(gatt)) return
            if (!gattOperationQueue?.complete(
                    kind = GattOperationKind.RSSI,
                    result = GattOperationResult(status = status, rssi = rssi),
                    callbackGattIdentity = gatt
                ).orFalse()
            ) return
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

        if (isScanning || activeScanCallback != null) {
            invalidateScan()
        }
        val generation = scanAttempts.begin()
        activeScanGeneration = generation
        val callback = createScanCallback(generation)
        activeScanCallback = callback
        isScanning = true
        Log.i(TAG, "Starting BLE scan for '${BleConstants.TARGET_DEVICE_NAME}'...")

        val scanFilter = ScanFilter.Builder()
            .setDeviceName(BleConstants.TARGET_DEVICE_NAME)
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(scanFilter), scanSettings, callback)
        } catch (e: Exception) {
            invalidateScan(generation)
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
            if (scanAttempts.isCurrent(generation) &&
                activeScanGeneration == generation &&
                isScanning &&
                _connectionState.value == ConnectionState.Connecting
            ) {
                val timeoutErr = "MotoNav-01 not found within 15s. Ensure the ESP32 is powered on and advertising."
                Log.w(TAG, timeoutErr)
                stopScan(generation)
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

    private fun createScanCallback(generation: Long): ScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanAttempts.isCurrent(generation) ||
                activeScanGeneration != generation ||
                !isScanning ||
                _connectionState.value != ConnectionState.Connecting ||
                bluetoothGatt != null ||
                gattOperationQueue != null
            ) return

            val device = result.device
            val deviceName = try {
                device.name
            } catch (e: SecurityException) {
                null
            } ?: result.scanRecord?.deviceName

            Log.d(TAG, "BLE Scan found device: $deviceName (${device.address}), RSSI: ${result.rssi}")

            if (deviceName == BleConstants.TARGET_DEVICE_NAME || device.name == BleConstants.TARGET_DEVICE_NAME) {
                Log.i(TAG, "Matched target device '${BleConstants.TARGET_DEVICE_NAME}' at ${device.address}")
                onTargetDeviceDiscovered(generation, device, result.rssi)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (!scanAttempts.isCurrent(generation) ||
                activeScanGeneration != generation ||
                !isScanning ||
                _connectionState.value != ConnectionState.Connecting
            ) return

            Log.e(TAG, "BLE Scan failed with errorCode: $errorCode")
            stopScan(generation)
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

    @SuppressLint("MissingPermission")
    private fun invalidateScan(generation: Long? = activeScanGeneration) {
        val callback = activeScanCallback
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanAttempts.invalidate(generation)
        activeScanGeneration = null
        isScanning = false
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        try {
            if (callback != null) scanner?.stopScan(callback)
            Log.d(TAG, "BLE scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scan", e)
        }
        activeScanCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(expectedGeneration: Long? = activeScanGeneration) {
        if (expectedGeneration != null && activeScanGeneration != expectedGeneration) return
        if (!isScanning && activeScanCallback == null) {
            scanAttempts.invalidate(expectedGeneration)
            return
        }
        invalidateScan(expectedGeneration)
    }

    @SuppressLint("MissingPermission")
    private fun onTargetDeviceDiscovered(
        generation: Long,
        device: BluetoothDevice,
        rssi: Int
    ) {
        if (!scanAttempts.isCurrent(generation) ||
            activeScanGeneration != generation ||
            !isScanning ||
            _connectionState.value != ConnectionState.Connecting ||
            bluetoothGatt != null ||
            gattOperationQueue != null ||
            !scanAttempts.claim(generation)
        ) return

        stopScan(generation)

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
        val epoch = nextConnectionEpoch.incrementAndGet()
        connectionEpoch = epoch
        bondCoordinator.begin(epoch, device.address)
        registerBondStateReceiver(epoch)
        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        bluetoothGatt = gatt
        gattOperationQueue = gatt?.let {
            GattOperationQueue(
                gattIdentity = it,
                epoch = epoch,
                scope = scope,
                onTimeout = { token ->
                    if (isCurrentGattToken(token)) {
                        disconnectAndCleanup("Timed out waiting for ${token.kind} GATT callback")
                    }
                },
                onCancellation = { token ->
                    if (isCurrentGattToken(token)) {
                        disconnectAndCleanup("GATT operation canceled")
                    }
                }
            )
        }
    }

    private fun isCurrentGattToken(token: GattOperationToken): Boolean =
        token.epoch == connectionEpoch &&
            bluetoothGatt === token.gattIdentity

    private fun registerBondStateReceiver(epoch: Long) {
        unregisterBondStateReceiver()
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                handleBondStateChanged(epoch, device, bondState, prevBondState)
            }
        }
        bondStateReceiver = receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isBondReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register bondStateReceiver", e)
        }
    }

    private fun unregisterBondStateReceiver() {
        val receiver = bondStateReceiver
        bondStateReceiver = null
        if (isBondReceiverRegistered && receiver != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering bondStateReceiver", e)
            } finally {
                isBondReceiverRegistered = false
            }
        } else {
            isBondReceiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectedBondState(gatt: BluetoothGatt, epoch: Long) {
        if (!isActiveGatt(gatt) || connectionEpoch != epoch) return
        val device = gatt.device ?: run {
            disconnectAndCleanup("GATT device is unavailable")
            return
        }
        val bondState = try {
            device.bondState
        } catch (e: SecurityException) {
            disconnectAndCleanup("Bluetooth connect permission missing for bond check")
            return
        }

        when (val action = bondCoordinator.onConnected(epoch, device.address, bondState)) {
            is BondAction.StartGattSetup -> {
                Log.i(TAG, "Device is already BOND_BONDED. Starting GATT setup...")
                _diagnostics.value = _diagnostics.value.copy(
                    isBonded = true,
                    isBonding = false,
                    disconnectReconnectState = "Connected and bonded. Discovering services..."
                )
                startGattSetup(gatt)
            }
            is BondAction.InitiateCreateBond -> {
                Log.i(TAG, "Device is BOND_NONE. Initiating createBond()...")
                _diagnostics.value = _diagnostics.value.copy(
                    isBonded = false,
                    isBonding = true,
                    disconnectReconnectState = "Pairing with MotoNav-01... Enter passkey on phone"
                )
                val initiated = try {
                    device.createBond()
                } catch (e: Exception) {
                    Log.e(TAG, "Exception calling createBond()", e)
                    false
                }
                if (!initiated) {
                    disconnectAndCleanup("Failed to initiate BLE bonding with MotoNav-01")
                }
            }
            is BondAction.WaitForBonding -> {
                Log.i(TAG, "Device is BOND_BONDING. Waiting for bond completion...")
                _diagnostics.value = _diagnostics.value.copy(
                    isBonded = false,
                    isBonding = true,
                    disconnectReconnectState = "Pairing with MotoNav-01 in progress..."
                )
            }
            is BondAction.PairingFailed -> {
                disconnectAndCleanup(action.message)
            }
            is BondAction.None -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBondStateChanged(
        epoch: Long,
        device: BluetoothDevice?,
        bondState: Int,
        prevBondState: Int
    ) {
        val gatt = bluetoothGatt ?: return
        if (!isActiveGatt(gatt)) return

        when (val action = bondCoordinator.onBondStateChanged(epoch, device?.address, bondState, prevBondState)) {
            is BondAction.StartGattSetup -> {
                Log.i(TAG, "Bonding complete (BOND_BONDED). Resuming GATT setup...")
                _diagnostics.value = _diagnostics.value.copy(
                    isBonded = true,
                    isBonding = false,
                    disconnectReconnectState = "Bonded with MotoNav-01. Discovering services..."
                )
                startGattSetup(gatt)
            }
            is BondAction.WaitForBonding -> {
                Log.i(TAG, "Bonding in progress (BOND_BONDING)...")
                _diagnostics.value = _diagnostics.value.copy(
                    isBonded = false,
                    isBonding = true,
                    disconnectReconnectState = "Pairing with MotoNav-01... Enter passkey on phone"
                )
            }
            is BondAction.PairingFailed -> {
                Log.w(TAG, "Bonding failed: ${action.message}")
                disconnectAndCleanup(action.message)
            }
            is BondAction.InitiateCreateBond -> Unit
            is BondAction.None -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGattSetup(gatt: BluetoothGatt) {
        val queue = gattOperationQueue
        if (queue == null || !isActiveGatt(gatt)) {
            disconnectAndCleanup("GATT operation queue unavailable")
            return
        }

        scope.launch {
            try {
                val mtuResult = queue.execute(
                    kind = GattOperationKind.MTU,
                    timeoutMs = 5_000L
                ) { token ->
                    val started = gatt.requestMtu(REQUESTED_ROUTE_ATT_MTU)
                    if (!started) {
                        Log.w(TAG, "Failed to request ATT MTU $REQUESTED_ROUTE_ATT_MTU")
                        queue.complete(
                            kind = GattOperationKind.MTU,
                            result = GattOperationResult(status = BluetoothGatt.GATT_FAILURE)
                        )
                    }
                    // A false return preserves the existing behavior: continue setup,
                    // leave negotiatedAttMtu unavailable, and block transfer later.
                    true
                }
                if (mtuResult.status != BluetoothGatt.GATT_SUCCESS) negotiatedAttMtu = null
            } catch (e: GattOperationStartException) {
                Log.w(TAG, "MTU request could not be started", e)
            } catch (e: GattOperationTimeoutException) {
                return@launch
            } catch (e: GattOperationInvalidatedException) {
                return@launch
            }

            if (!isActiveGatt(gatt)) return@launch
            try {
                val discoveryResult = queue.execute(
                    kind = GattOperationKind.SERVICE_DISCOVERY,
                    timeoutMs = 10_000L
                ) {
                    val started = gatt.discoverServices()
                    if (!started) {
                        queue.complete(
                            kind = GattOperationKind.SERVICE_DISCOVERY,
                            result = GattOperationResult(status = BluetoothGatt.GATT_FAILURE)
                        )
                    }
                    true
                }
                if (discoveryResult.status != BluetoothGatt.GATT_SUCCESS) {
                    disconnectAndCleanup("Failed to start GATT service discovery")
                }
            } catch (_: GattOperationTimeoutException) {
                return@launch
            } catch (_: GattOperationInvalidatedException) {
                return@launch
            } catch (e: GattOperationStartException) {
                disconnectAndCleanup("Failed to start GATT service discovery")
            }
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

        val cccdEnableValue = BleCharacteristicPropertyPolicy.cccdEnableValue(statusChar.properties)
        if (cccdEnableValue == null) {
            val errorMessage = "Status characteristic supports neither NOTIFY nor INDICATE"
            Log.e(TAG, errorMessage)
            disconnectAndCleanup(errorMessage)
            return
        }

        val queue = gattOperationQueue
        if (queue == null) {
            disconnectAndCleanup("GATT operation queue unavailable for CCCD write")
            return
        }

        scope.launch {
            try {
                val result = queue.execute(
                    kind = GattOperationKind.DESCRIPTOR_WRITE,
                    targetIdentity = descriptor,
                    targetUuid = descriptor.uuid,
                    timeoutMs = 5_000L
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val code = gatt.writeDescriptor(descriptor, cccdEnableValue)
                        if (code != BluetoothStatusCodes.SUCCESS) {
                            queue.complete(
                                kind = GattOperationKind.DESCRIPTOR_WRITE,
                                targetIdentity = descriptor,
                                targetUuid = descriptor.uuid,
                                result = GattOperationResult(status = code)
                            )
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = cccdEnableValue
                        @Suppress("DEPRECATION")
                        val started = gatt.writeDescriptor(descriptor)
                        if (!started) {
                            queue.complete(
                                kind = GattOperationKind.DESCRIPTOR_WRITE,
                                targetIdentity = descriptor,
                                targetUuid = descriptor.uuid,
                                result = GattOperationResult(status = BluetoothGatt.GATT_FAILURE)
                            )
                        }
                    }
                    true
                }
                if (result.status != BluetoothGatt.GATT_SUCCESS) {
                    disconnectAndCleanup("CCCD descriptor write failed with status ${result.status}")
                }
            } catch (_: GattOperationTimeoutException) {
                // Queue timeout invalidates and closes the GATT.
            } catch (_: GattOperationInvalidatedException) {
                // Disconnect/cleanup owns the resulting state.
            } catch (e: GattOperationStartException) {
                val err = "Error starting CCCD descriptor write: ${e.message}"
                Log.e(TAG, err)
                _lastError.value = err
                _diagnostics.value = _diagnostics.value.copy(lastError = err)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readStatusCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        description: String = "status"
    ) {
        val queue = gattOperationQueue
        if (queue == null) {
            val err = "Cannot read $description: GATT operation queue unavailable"
            Log.e(TAG, err)
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
            return
        }

        scope.launch {
            try {
                val result = queue.execute(
                    kind = GattOperationKind.CHARACTERISTIC_READ,
                    targetIdentity = characteristic,
                    targetUuid = characteristic.uuid,
                    timeoutMs = 5_000L
                ) {
                    @Suppress("DEPRECATION")
                    val started = gatt.readCharacteristic(characteristic)
                    if (!started) {
                        queue.complete(
                            kind = GattOperationKind.CHARACTERISTIC_READ,
                            targetIdentity = characteristic,
                            targetUuid = characteristic.uuid,
                            result = GattOperationResult(status = BluetoothGatt.GATT_FAILURE)
                        )
                    }
                    true
                }
                if (result.status != BluetoothGatt.GATT_SUCCESS) {
                    val err = "Read $description failed with status ${result.status}"
                    Log.w(TAG, err)
                    _lastError.value = err
                    _diagnostics.value = _diagnostics.value.copy(lastError = err)
                }
            } catch (_: GattOperationTimeoutException) {
                // Queue timeout invalidates and closes the GATT.
            } catch (_: GattOperationInvalidatedException) {
                // Disconnect/cleanup owns the resulting state.
            } catch (e: GattOperationStartException) {
                val err = "Error starting $description read: ${e.message}"
                Log.e(TAG, err)
                _lastError.value = err
                _diagnostics.value = _diagnostics.value.copy(lastError = err)
            }
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
        readStatusCharacteristic(gatt, statusChar, "status")
    }

    private fun queueStatusRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        description: String
    ) {
        readStatusCharacteristic(gatt, characteristic, description)
    }

    @SuppressLint("MissingPermission")
    private fun queueRssiRead(gatt: BluetoothGatt) {
        val queue = gattOperationQueue ?: return
        if (!isActiveGatt(gatt)) return
        scope.launch {
            try {
                val result = queue.execute(
                    kind = GattOperationKind.RSSI,
                    timeoutMs = 5_000L
                ) {
                    val started = gatt.readRemoteRssi()
                    if (!started) {
                        queue.complete(
                            kind = GattOperationKind.RSSI,
                            result = GattOperationResult(status = BluetoothGatt.GATT_FAILURE)
                        )
                    }
                    true
                }
                if (result.status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Initial RSSI read failed with status ${result.status}")
                }
            } catch (_: GattOperationTimeoutException) {
                // Queue timeout invalidates and closes the GATT.
            } catch (_: GattOperationInvalidatedException) {
                // Disconnect/cleanup owns the resulting state.
            } catch (e: GattOperationStartException) {
                Log.e(TAG, "Error starting initial RSSI read: ${e.message}")
            }
        }
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
        invalidateScan()
        statusNotificationGate.resetConnection()
        transferTerminalBoundary.reset()
        bondCoordinator.reset()
        unregisterBondStateReceiver()
        val generation = activeTransferGeneration
        activeTransferGeneration = 0L
        cancellingTransferGeneration = null
        transferJob?.cancel()
        ackSynchronizer.deactivate(generation.takeIf { it != 0L })
        connectionEpoch = nextConnectionEpoch.incrementAndGet()
        val gatt = bluetoothGatt
        val queue = gattOperationQueue
        gattOperationQueue = null
        bluetoothGatt = null
        queue?.invalidate(CancellationException("GATT disconnected"))
        gatt?.let { activeGatt ->
            try {
                activeGatt.disconnect()
                activeGatt.close()
                Log.d(TAG, "GATT disconnected and closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting GATT", e)
            }
        }
        negotiatedAttMtu = null
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
            isBonding = false,
            isBonded = false,
            disconnectReconnectState = "Disconnected",
            servicesDiscovered = false,
            serviceUuidFound = false,
            controlCharacteristicFound = false,
            statusCharacteristicFound = false,
            routeDataCharacteristicFound = false,
            statusNotificationsEnabled = false
        )
    }

    @SuppressLint("MissingPermission")
    private fun disconnectAndCleanup(errorMessage: String?) {
        invalidateScan()
        statusNotificationGate.resetConnection()
        transferTerminalBoundary.reset()
        bondCoordinator.reset()
        unregisterBondStateReceiver()
        val generation = activeTransferGeneration
        activeTransferGeneration = 0L
        cancellingTransferGeneration = null
        transferJob?.cancel()
        ackSynchronizer.deactivate(generation.takeIf { it != 0L })
        connectionEpoch = nextConnectionEpoch.incrementAndGet()
        val queue = gattOperationQueue
        gattOperationQueue = null
        val gatt = bluetoothGatt
        bluetoothGatt = null
        queue?.invalidate(CancellationException("GATT disconnected"))

        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT in cleanup", e)
        }
        negotiatedAttMtu = null
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
            isBonding = false,
            isBonded = false,
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
        charDescription: String = characteristic.uuid.toString(),
        allowDuringCancellation: Boolean = false
    ): Boolean {
        if (activeTransferGeneration != generation ||
            (!allowDuringCancellation && isTransferCancelling(generation))
        ) return false

        val queue = gattOperationQueue ?: return false

        if (!BleCharacteristicPropertyPolicy.supportsResponseWrite(characteristic.properties)) {
            Log.e(TAG, "[GATT_WRITE_FAIL] $charDescription does not support WRITE")
            return false
        }
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        try {
            val result = queue.execute(
                kind = GattOperationKind.CHARACTERISTIC_WRITE,
                targetIdentity = characteristic,
                targetUuid = characteristic.uuid,
                transferGeneration = generation,
                timeoutMs = 5_000L
            ) {
                Log.d(TAG, "[GATT_WRITE_START] Writing ${data.size} bytes to $charDescription (writeType: $writeType)")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val code = gatt.writeCharacteristic(characteristic, data, writeType)
                    if (code != BluetoothStatusCodes.SUCCESS) {
                        queue.complete(
                            kind = GattOperationKind.CHARACTERISTIC_WRITE,
                            targetIdentity = characteristic,
                            targetUuid = characteristic.uuid,
                            result = GattOperationResult(status = code)
                        )
                    }
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = data
                    @Suppress("DEPRECATION")
                    characteristic.writeType = writeType
                    @Suppress("DEPRECATION")
                    val started = gatt.writeCharacteristic(characteristic)
                    if (!started) {
                        queue.complete(
                            kind = GattOperationKind.CHARACTERISTIC_WRITE,
                            targetIdentity = characteristic,
                            targetUuid = characteristic.uuid,
                            result = GattOperationResult(status = BluetoothGatt.GATT_FAILURE)
                        )
                    }
                }
                true
            }
            val status = result.status
            val success = (status == BluetoothGatt.GATT_SUCCESS)
            Log.d(TAG, "[GATT_WRITE_END] Write to $charDescription finished with status=$status (success=$success)")
            return success
        } catch (_: GattOperationTimeoutException) {
            Log.e(TAG, "[GATT_WRITE_TIMEOUT] Timed out writing to $charDescription")
            return false
        } catch (_: GattOperationInvalidatedException) {
            return false
        } catch (e: Exception) {
            Log.e(TAG, "[GATT_WRITE_ERROR] Exception during write to $charDescription: ${e.message}")
            return false
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

    @SuppressLint("MissingPermission")
    private fun transferBinary(binary: ByteArray, crc32: Long, isTestRoute: Boolean = false) {
        val actionName = if (isTestRoute) "test route" else "route"

        // 1. Boundary validation: reject payloads below minimum header size (9 bytes)
        if (binary.size < 9) {
            val err = "Cannot send $actionName: route binary size (${binary.size} bytes) is below minimum header size (9 bytes)"
            Log.e(TAG, err)
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
            return
        }

        // 2. CRC range validation: reject values outside unsigned 32-bit range (0L..0xFFFFFFFFL)
        if (crc32 !in 0L..0xFFFFFFFFL) {
            val err = "Cannot send $actionName: CRC32 ($crc32) is outside valid 32-bit unsigned range (0..0xFFFFFFFF)"
            Log.e(TAG, err)
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
            return
        }

        // 3. CRC sanity validation: reject if supplied CRC does not match calculated binary CRC32
        val calculatedCrc = TestRouteFixture.calculateCrc32(binary)
        if (calculatedCrc != crc32) {
            val err = "Cannot send $actionName: CRC mismatch (provided 0x${crc32.toString(16).uppercase()}, calculated 0x${calculatedCrc.toString(16).uppercase()})"
            Log.e(TAG, err)
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
            return
        }

        // 4. Verify BLE is connected before starting
        val gatt = bluetoothGatt
        if (gatt == null || (_connectionState.value != ConnectionState.Connected && _connectionState.value != ConnectionState.RouteReady)) {
            val err = "Cannot send $actionName: MotoNav-01 is not connected"
            Log.e(TAG, err)
            _lastError.value = err
            _diagnostics.value = _diagnostics.value.copy(lastError = err)
            return
        }

        // 5. Require the active device to be securely paired and bonded before START_ROUTE
        val device = gatt.device
        val bondState = try {
            device?.bondState
        } catch (e: SecurityException) {
            null
        }
        if (device == null || !bondCoordinator.isBonded(bondState)) {
            val bondDesc = when (bondState) {
                BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
                BluetoothDevice.BOND_NONE -> "BOND_NONE"
                BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
                else -> "UNKNOWN"
            }
            val err = "Cannot send $actionName: MotoNav-01 is not securely paired/bonded (bondState=$bondDesc)"
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

        if (!isRouteDataMtuSufficient(negotiatedAttMtu)) {
            val err = "Cannot send $actionName: negotiated ATT MTU is unavailable or below $REQUESTED_ROUTE_ATT_MTU (actual=${negotiatedAttMtu ?: "unknown"})"
            Log.e(TAG, err)
            _lastError.value = err
            _connectionState.value = ConnectionState.Error
            _diagnostics.value = _diagnostics.value.copy(
                lastError = err,
                disconnectReconnectState = "Route transfer blocked by insufficient ATT MTU"
            )
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
        statusNotificationGate.markTransferStarted()
        transferTerminalBoundary.begin(transferGeneration)
        activeTransferGeneration = transferGeneration
        cancellingTransferGeneration = null
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
                    if (isTransferCancelling(transferGeneration)) return@launch
                    ackSynchronizer.deactivate(transferGeneration)
                    handleTransferError(transferGeneration, "BLE write failure: unable to send START_ROUTE command to Control characteristic")
                    return@launch
                }
                Log.i(TAG, "START_ROUTE command successfully written to Control characteristic")

                // 5. Transfer each packet: write -> wait for matching ACK,<sequence>
                for (packet in packets) {
                    if (activeTransferGeneration != transferGeneration ||
                        isTransferCancelling(transferGeneration) ||
                        _connectionState.value != ConnectionState.Transferring ||
                        bluetoothGatt == null
                    ) {
                        if (isTransferCancelling(transferGeneration)) return@launch
                        ackSynchronizer.deactivate(transferGeneration)
                        handleTransferError(transferGeneration, "Disconnect occurred during route transfer")
                        return@launch
                    }

                    Log.i(TAG, "[ROUTE_DATA_WRITE] Writing ROUTE_DATA packet ${packet.sequence} (${packet.payloadLength} B payload, ${packet.framedBytes.size} B framed)...")
                    val writeOk = writeCharacteristicSuspend(transferGeneration, gatt, routeData, packet.framedBytes, "RouteData (packet ${packet.sequence})")
                    if (!writeOk) {
                        if (isTransferCancelling(transferGeneration)) return@launch
                        ackSynchronizer.deactivate(transferGeneration)
                        handleTransferError(transferGeneration, "BLE write failure on Route Data characteristic for packet ${packet.sequence}")
                        return@launch
                    }
                    Log.i(TAG, "[ROUTE_DATA_WRITE] Packet ${packet.sequence} write finished. Waiting for ACK,${packet.sequence}...")

                    val ackResult = ackSynchronizer.waitForAck(expectedSequence = packet.sequence, timeoutMs = 5000L)
                    if (isTransferCancelling(transferGeneration)) return@launch
                    when (ackResult) {
                        is AckWaitResult.Success -> {
                            if (activeTransferGeneration != transferGeneration || isTransferCancelling(transferGeneration)) return@launch
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
                if (activeTransferGeneration != transferGeneration || isTransferCancelling(transferGeneration)) return@launch
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
                    if (isTransferCancelling(transferGeneration)) return@launch
                    handleTransferError(transferGeneration, "BLE write failure: unable to send END_ROUTE command to Control characteristic")
                    return@launch
                }

                // 7. Read/poll Status and require ROUTE_READY
                Log.i(TAG, "END_ROUTE written. Verifying route status 'ROUTE_READY'...")
                if (activeTransferGeneration != transferGeneration || isTransferCancelling(transferGeneration)) return@launch
                _transferProgress.value = RouteTransferProgress(
                    percentage = 100,
                    currentPacket = totalPackets,
                    totalPackets = totalPackets,
                    stepDescription = "Verifying route CRC on MotoNav-01..."
                )

                readStatusCharacteristic(gatt, statusChar, "route verification")
                val verificationEvent = routeReadyEventTracker.awaitVerificationEvent(
                    generation = routeReadyGeneration,
                    timeoutMs = 6000L
                )
                if (verificationEvent is RouteVerificationEvent.Error) {
                    if (isTransferCancelling(transferGeneration)) return@launch
                    handleTransferError(transferGeneration, "MotoNav-01 reported error during route verification: '${verificationEvent.message}'")
                    return@launch
                }
                if (verificationEvent !is RouteVerificationEvent.Ready) {
                    if (isTransferCancelling(transferGeneration)) return@launch
                    handleTransferError(transferGeneration, "Route verification failed: expected 'ROUTE_READY' but received '${_diagnostics.value.lastStatusMessage ?: "timeout"}'")
                    return@launch
                }

                // 8. Display transfer progress and final result
                if (activeTransferGeneration != transferGeneration || isTransferCancelling(transferGeneration)) return@launch
                terminateTransferGeneration(transferGeneration)
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
                if (isTransferCancelling(transferGeneration)) return@launch
                handleTransferError(transferGeneration, err)
            } finally {
                ackSynchronizer.deactivate(transferGeneration)
            }
        }
    }

    private fun handleTransferError(generation: Long, errorMessage: String) {
        if (activeTransferGeneration != generation) return
        terminateTransferGeneration(generation)
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

    private fun terminateTransferGeneration(generation: Long): Boolean {
        val terminated = transferTerminalBoundary.terminate(generation)
        if (terminated && activeTransferGeneration == generation) {
            activeTransferGeneration = 0L
        }
        return terminated
    }

    override fun cancelTransfer() {
        val generation = activeTransferGeneration
        if (generation == 0L || _connectionState.value != ConnectionState.Transferring) return

        cancellingTransferGeneration = generation

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
                    charDescription = "Control (CANCEL_ROUTE)",
                    allowDuringCancellation = true
                )
            } else {
                false
            }

            if (activeTransferGeneration != generation) return@launch

            activeTransferGeneration = 0L
            cancellingTransferGeneration = null
            transferJob?.cancel()
            ackSynchronizer.deactivate(generation)

            if (cancelSucceeded) {
                statusNotificationGate.markTransferCancelled()
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
