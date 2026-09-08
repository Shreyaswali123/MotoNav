package com.example

import com.example.ble.BleConstants
import com.example.ble.MockBleRepository
import com.example.model.ConnectionState
import com.example.ui.screens.device.DeviceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleVerificationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialDiagnosticsDisconnected() {
        val repo = MockBleRepository()
        val viewModel = DeviceViewModel(bleRepository = repo)

        val diag = viewModel.diagnostics.value
        assertFalse(diag.isScanning)
        assertFalse(diag.deviceDiscovered)
        assertFalse(diag.isConnected)
        assertFalse(diag.servicesDiscovered)
        assertFalse(diag.controlCharacteristicFound)
        assertFalse(diag.statusCharacteristicFound)
        assertFalse(diag.routeDataCharacteristicFound)
        assertFalse(diag.statusNotificationsEnabled)
        assertFalse(diag.initialStatusRead)
        assertNull(diag.lastStatusMessage)
        assertNull(diag.lastError)
    }

    @Test
    fun testConnectingAndReadStatusFlow() = runTest(testDispatcher) {
        val repo = MockBleRepository()
        val viewModel = DeviceViewModel(bleRepository = repo)

        // Trigger connect
        viewModel.connect()
        assertTrue(viewModel.diagnostics.value.isScanning)

        // Advance to discovery & connected
        testScheduler.advanceTimeBy(1500)

        val connectedDiag = viewModel.diagnostics.value
        assertFalse(connectedDiag.isScanning)
        assertTrue(connectedDiag.deviceDiscovered)
        assertEquals(BleConstants.TARGET_DEVICE_NAME, connectedDiag.discoveredDeviceName)
        assertTrue(connectedDiag.isConnected)
        assertTrue(connectedDiag.servicesDiscovered)
        assertTrue(connectedDiag.serviceUuidFound)
        assertTrue(connectedDiag.controlCharacteristicFound)
        assertTrue(connectedDiag.statusCharacteristicFound)
        assertTrue(connectedDiag.routeDataCharacteristicFound)
        assertTrue(connectedDiag.statusNotificationsEnabled)
        assertTrue(connectedDiag.initialStatusRead)
        assertEquals("IDLE,0/0", connectedDiag.lastStatusMessage)
        assertNotNull(connectedDiag.lastStatusReadTimestamp)

        // Trigger explicit developer read status action
        viewModel.readStatus()
        val readDiag = viewModel.diagnostics.value
        assertEquals("IDLE,0/0", readDiag.lastStatusMessage)
        assertNotNull(readDiag.lastStatusReadTimestamp)
        assertTrue(readDiag.disconnectReconnectState.startsWith("Status read OK"))
    }

    @Test
    fun testReadStatusWhileDisconnectedReportsError() {
        val repo = MockBleRepository()
        val viewModel = DeviceViewModel(bleRepository = repo)

        viewModel.readStatus()
        assertNotNull(viewModel.diagnostics.value.lastError)
        assertTrue(viewModel.diagnostics.value.lastError!!.contains("disconnected", ignoreCase = true))
    }

    @Test
    fun testDisconnectResetsConnectionDiagnostics() = runTest(testDispatcher) {
        val repo = MockBleRepository()
        val viewModel = DeviceViewModel(bleRepository = repo)

        viewModel.connect()
        testScheduler.advanceTimeBy(1500)
        assertTrue(viewModel.diagnostics.value.isConnected)

        viewModel.disconnect()
        val diag = viewModel.diagnostics.value
        assertFalse(diag.isConnected)
        assertFalse(diag.isScanning)
        assertFalse(diag.isConnecting)
        assertFalse(diag.statusNotificationsEnabled)
        assertEquals("Disconnected", diag.disconnectReconnectState)
    }
}
