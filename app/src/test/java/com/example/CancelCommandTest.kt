package com.example

import com.example.ble.BleConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CancelCommandTest {

    @Test
    fun cancelUsesExistingFirmwareControlCommand() {
        assertEquals(0x03.toByte(), BleConstants.CMD_CANCEL_ROUTE)
        assertArrayEquals(byteArrayOf(0x03), byteArrayOf(BleConstants.CMD_CANCEL_ROUTE))
    }
}