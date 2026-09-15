package com.example

import android.bluetooth.BluetoothDevice
import com.example.ble.BleBondCoordinator
import com.example.ble.BondAction
import com.example.model.BleDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBleSecurityBondingTest {

    @Test
    fun alreadyBondedProceedsWithGattSetupWithoutCreatingBond() {
        val coordinator = BleBondCoordinator()
        coordinator.begin(epoch = 1L, deviceAddress = "AA:BB:CC:DD:EE:FF")

        val action = coordinator.onConnected(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_BONDED
        )

        assertEquals(BondAction.StartGattSetup, action)
        assertTrue(coordinator.isGattSetupStarted())
        assertFalse(coordinator.isPairingInProgress())

        // Ensure duplicate event does not start GATT setup again
        val duplicateAction = coordinator.onBondStateChanged(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_BONDED,
            prevBondState = BluetoothDevice.BOND_BONDED
        )
        assertEquals(BondAction.None, duplicateAction)
    }

    @Test
    fun unbondedInitiatesCreateBondAndDoesNotStartGattSetup() {
        val coordinator = BleBondCoordinator()
        coordinator.begin(epoch = 1L, deviceAddress = "AA:BB:CC:DD:EE:FF")

        val action = coordinator.onConnected(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_NONE
        )

        assertEquals(BondAction.InitiateCreateBond, action)
        assertFalse(coordinator.isGattSetupStarted())
        assertTrue(coordinator.isPairingInProgress())
    }

    @Test
    fun bondingStateWaitsForCompletion() {
        val coordinator = BleBondCoordinator()
        coordinator.begin(epoch = 1L, deviceAddress = "AA:BB:CC:DD:EE:FF")

        val action = coordinator.onConnected(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_BONDING
        )

        assertEquals(BondAction.WaitForBonding, action)
        assertFalse(coordinator.isGattSetupStarted())
        assertTrue(coordinator.isPairingInProgress())
    }

    @Test
    fun pairingSuccessResumesGattSetupExactlyOnce() {
        val coordinator = BleBondCoordinator()
        coordinator.begin(epoch = 1L, deviceAddress = "AA:BB:CC:DD:EE:FF")

        // 1. Initial connection in unbonded state
        val connectAction = coordinator.onConnected(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_NONE
        )
        assertEquals(BondAction.InitiateCreateBond, connectAction)
        assertFalse(coordinator.isGattSetupStarted())
        assertTrue(coordinator.isPairingInProgress())

        // 2. Transition to BOND_BONDING
        val bondingAction = coordinator.onBondStateChanged(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_BONDING,
            prevBondState = BluetoothDevice.BOND_NONE
        )
        assertEquals(BondAction.WaitForBonding, bondingAction)
        assertFalse(coordinator.isGattSetupStarted())
        assertTrue(coordinator.isPairingInProgress())

        // 3. Pairing completes successfully -> BOND_BONDED
        val bondedAction = coordinator.onBondStateChanged(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_BONDED,
            prevBondState = BluetoothDevice.BOND_BONDING
        )
        assertEquals(BondAction.StartGattSetup, bondedAction)
        assertTrue(coordinator.isGattSetupStarted())
        assertFalse(coordinator.isPairingInProgress())

        // 4. Stale/duplicate bonded broadcast is ignored
        val duplicateAction = coordinator.onBondStateChanged(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_BONDED,
            prevBondState = BluetoothDevice.BOND_BONDED
        )
        assertEquals(BondAction.None, duplicateAction)
    }

    @Test
    fun pairingFailureOrCancellationTriggersCleanFailure() {
        val coordinator = BleBondCoordinator()
        coordinator.begin(epoch = 1L, deviceAddress = "AA:BB:CC:DD:EE:FF")

        coordinator.onConnected(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_NONE
        )

        coordinator.onBondStateChanged(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_BONDING,
            prevBondState = BluetoothDevice.BOND_NONE
        )

        val failureAction = coordinator.onBondStateChanged(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_NONE,
            prevBondState = BluetoothDevice.BOND_BONDING
        )

        assertTrue(failureAction is BondAction.PairingFailed)
        assertFalse(coordinator.isPairingInProgress())
        assertFalse(coordinator.isGattSetupStarted())
    }

    @Test
    fun strayBondNoneIgnoredWhenPairingWasNotInProgress() {
        val coordinator = BleBondCoordinator()
        coordinator.begin(epoch = 1L, deviceAddress = "AA:BB:CC:DD:EE:FF")

        val action = coordinator.onBondStateChanged(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_NONE,
            prevBondState = BluetoothDevice.BOND_NONE
        )

        assertEquals(BondAction.None, action)
        assertFalse(coordinator.isPairingInProgress())
        assertFalse(coordinator.isGattSetupStarted())
    }

    @Test
    fun staleOrMismatchedEpochIgnored() {
        val coordinator = BleBondCoordinator()
        coordinator.begin(epoch = 2L, deviceAddress = "AA:BB:CC:DD:EE:FF")

        val staleConnect = coordinator.onConnected(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_BONDED
        )
        assertEquals(BondAction.None, staleConnect)

        val staleBondState = coordinator.onBondStateChanged(
            epoch = 1L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            bondState = BluetoothDevice.BOND_BONDED,
            prevBondState = BluetoothDevice.BOND_BONDING
        )
        assertEquals(BondAction.None, staleBondState)
    }

    @Test
    fun staleOrMismatchedDeviceAddressIgnored() {
        val coordinator = BleBondCoordinator()
        coordinator.begin(epoch = 1L, deviceAddress = "AA:BB:CC:DD:EE:FF")

        val wrongDeviceConnect = coordinator.onConnected(
            epoch = 1L,
            deviceAddress = "11:22:33:44:55:66",
            bondState = BluetoothDevice.BOND_BONDED
        )
        assertEquals(BondAction.None, wrongDeviceConnect)

        val wrongDeviceBondState = coordinator.onBondStateChanged(
            epoch = 1L,
            deviceAddress = "11:22:33:44:55:66",
            bondState = BluetoothDevice.BOND_BONDED,
            prevBondState = BluetoothDevice.BOND_BONDING
        )
        assertEquals(BondAction.None, wrongDeviceBondState)
    }

    @Test
    fun routeTransferRequiresBondedState() {
        val coordinator = BleBondCoordinator()

        assertFalse(coordinator.isBonded(BluetoothDevice.BOND_NONE))
        assertFalse(coordinator.isBonded(BluetoothDevice.BOND_BONDING))
        assertFalse(coordinator.isBonded(null))
        assertTrue(coordinator.isBonded(BluetoothDevice.BOND_BONDED))
    }

    @Test
    fun diagnosticsSupportBondingAndBondedFields() {
        val initial = BleDiagnostics()
        assertFalse(initial.isBonding)
        assertFalse(initial.isBonded)

        val bonding = initial.copy(isBonding = true, isBonded = false)
        assertTrue(bonding.isBonding)
        assertFalse(bonding.isBonded)

        val bonded = bonding.copy(isBonding = false, isBonded = true)
        assertFalse(bonded.isBonding)
        assertTrue(bonded.isBonded)
    }

    @Test
    fun resetClearsAllState() {
        val coordinator = BleBondCoordinator()
        coordinator.begin(epoch = 1L, deviceAddress = "AA:BB:CC:DD:EE:FF")
        coordinator.onConnected(1L, "AA:BB:CC:DD:EE:FF", BluetoothDevice.BOND_BONDED)

        assertTrue(coordinator.isGattSetupStarted())
        coordinator.reset()

        assertFalse(coordinator.isGattSetupStarted())
        assertFalse(coordinator.isPairingInProgress())
        assertEquals(null, coordinator.activeEpoch())
        assertEquals(null, coordinator.activeDeviceAddress())
    }
}
