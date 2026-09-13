package com.example

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import com.example.ble.BleCharacteristicPropertyPolicy
import com.example.ble.StatusNotificationGate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBleCharacteristicPropertiesTest {

    @Test
    fun controlRequiresResponseWrite() {
        assertTrue(BleCharacteristicPropertyPolicy.supportsResponseWrite(BluetoothGattCharacteristic.PROPERTY_WRITE))
        assertFalse(BleCharacteristicPropertyPolicy.supportsResponseWrite(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))
        assertFalse(BleCharacteristicPropertyPolicy.supportsResponseWrite(0))
    }

    @Test
    fun routeDataRequiresResponseWrite() {
        assertTrue(BleCharacteristicPropertyPolicy.supportsResponseWrite(BluetoothGattCharacteristic.PROPERTY_WRITE))
        assertFalse(BleCharacteristicPropertyPolicy.supportsResponseWrite(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))
        assertFalse(BleCharacteristicPropertyPolicy.supportsResponseWrite(0))
    }

    @Test
    fun statusNotifyOnlyUsesNotificationCccdValue() {
        val value = BleCharacteristicPropertyPolicy.cccdEnableValue(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

        assertArrayEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)
        assertTrue(BleCharacteristicPropertyPolicy.supportsStatusUpdates(BluetoothGattCharacteristic.PROPERTY_NOTIFY))
    }

    @Test
    fun statusIndicateOnlyUsesIndicationCccdValue() {
        val value = BleCharacteristicPropertyPolicy.cccdEnableValue(BluetoothGattCharacteristic.PROPERTY_INDICATE)

        assertArrayEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE, value)
        assertTrue(BleCharacteristicPropertyPolicy.supportsStatusUpdates(BluetoothGattCharacteristic.PROPERTY_INDICATE))
    }

    @Test
    fun statusWithBothPropertiesPrefersNotification() {
        val properties = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE

        assertArrayEquals(
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            BleCharacteristicPropertyPolicy.cccdEnableValue(properties)
        )
        assertTrue(BleCharacteristicPropertyPolicy.supportsStatusUpdates(properties))
    }

    @Test
    fun statusWithoutNotifyOrIndicateIsRejected() {
        assertFalse(BleCharacteristicPropertyPolicy.supportsStatusUpdates(BluetoothGattCharacteristic.PROPERTY_READ))
        assertNull(BleCharacteristicPropertyPolicy.cccdEnableValue(BluetoothGattCharacteristic.PROPERTY_READ))
        assertFalse(BleCharacteristicPropertyPolicy.supportsStatusUpdates(0))
    }

    @Test
    fun statusReadIsOptionalWhenNotifyIsSupported() {
        val properties = BluetoothGattCharacteristic.PROPERTY_NOTIFY

        assertTrue(BleCharacteristicPropertyPolicy.supportsStatusUpdates(properties))
    }

    @Test
    fun statusReadIsOptionalWhenIndicateIsSupported() {
        val properties = BluetoothGattCharacteristic.PROPERTY_INDICATE

        assertTrue(BleCharacteristicPropertyPolicy.supportsStatusUpdates(properties))
    }

    @Test
    fun unsupportedWriteCapabilitiesCannotBeAcceptedAsTransferCapabilities() {
        assertFalse(BleCharacteristicPropertyPolicy.supportsResponseWrite(0))
        assertFalse(BleCharacteristicPropertyPolicy.supportsResponseWrite(BluetoothGattCharacteristic.PROPERTY_READ))
    }

    @Test
    fun validStatusCapabilityRemainsCompatibleWithExistingStatusGate() {
        val gate = StatusNotificationGate()

        assertTrue(BleCharacteristicPropertyPolicy.supportsStatusUpdates(BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        assertTrue(gate.shouldForward("RECEIVING,1/2"))
        assertTrue(gate.shouldForward("VERIFYING,2/2"))
        assertTrue(gate.shouldForward("ROUTE_READY,2/2"))
        assertTrue(gate.shouldForward("ERROR,1/2"))
        assertTrue(gate.shouldForward("ACK,0"))
    }

    @Test
    fun staleStatusGateStillSuppressesAfterValidPropertySelection() {
        val gate = StatusNotificationGate()
        gate.markTransferCancelled()

        assertTrue(BleCharacteristicPropertyPolicy.supportsStatusUpdates(BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        assertFalse(gate.shouldForward("ROUTE_READY,2/2"))
        assertFalse(gate.shouldForward("ERROR,1/2"))
    }
}