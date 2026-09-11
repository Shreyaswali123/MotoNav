package com.example

import com.example.ble.AckSynchronizer
import com.example.ble.AckWaitResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AckSynchronizationTest {

    @Test
    fun testRawNotificationByteDecodingHexToUtf8() {
        // Raw byte array: 41 43 4B 2C 30
        val rawBytes = byteArrayOf(0x41, 0x43, 0x4B, 0x2C, 0x30)
        val hexString = rawBytes.joinToString(" ") { "%02X".format(it) }
        val decodedString = String(rawBytes, Charsets.UTF_8).trim()

        assertEquals("41 43 4B 2C 30", hexString)
        assertEquals("ACK,0", decodedString)
    }

    @Test
    fun testSequenceParserExtractsIntegersCorrectly() {
        // Test extraction of integer 0, 1, 2, 3
        val testCases = listOf(
            "ACK,0" to 0,
            "ACK,1" to 1,
            "ACK,2" to 2,
            "ACK,3" to 3,
            "ACK, 0 " to 0,
            "ACK,10" to 10
        )

        for ((input, expectedSeq) in testCases) {
            val parts = input.split(",")
            assertEquals("ACK", parts[0].trim().uppercase())
            val seq = parts.getOrNull(1)?.trim()?.toIntOrNull()
            assertEquals("Failed to parse integer from $input", expectedSeq, seq)
        }
    }

    @Test
    fun testImmediateAckArrivingBeforeOrImmediatelyAfterWriteReturns() = runTest {
        val synchronizer = AckSynchronizer(tag = "TestSync")
        // 1. Activate listener BEFORE write (as required by the architecture)
        synchronizer.activate()
        assertTrue(synchronizer.isActive)

        // 2. Simulate ESP32 sending ACK,0 immediately during or right after write
        // Notice this happens BEFORE waitForAck is even invoked!
        val rawBytes = byteArrayOf(0x41, 0x43, 0x4B, 0x2C, 0x30) // "ACK,0"
        val decoded = String(rawBytes, Charsets.UTF_8).trim()
        synchronizer.onNotificationReceived(decoded)

        // 3. Now the coroutine awaits ACK for packet 0
        val result = synchronizer.waitForAck(expectedSequence = 0, timeoutMs = 1000L)
        assertTrue("Result should be Success", result is AckWaitResult.Success)
        val success = result as AckWaitResult.Success
        assertEquals(0, success.sequence)
        assertEquals("ACK,0", success.raw)

        synchronizer.deactivate()
    }

    @Test
    fun testAckArrivingDuringWait() = runTest {
        val synchronizer = AckSynchronizer(tag = "TestSync")
        synchronizer.activate()

        // Launch delayed notification arrival (simulating BLE callback firing while waiter is suspended)
        launch {
            delay(50)
            synchronizer.onNotificationReceived("ACK,1")
        }

        val result = synchronizer.waitForAck(expectedSequence = 1, timeoutMs = 2000L)
        assertTrue("Result should be Success", result is AckWaitResult.Success)
        val success = result as AckWaitResult.Success
        assertEquals(1, success.sequence)
        assertEquals("ACK,1", success.raw)

        synchronizer.deactivate()
    }

    @Test
    fun testSequenceMismatchDetection() = runTest {
        val synchronizer = AckSynchronizer(tag = "TestSync")
        synchronizer.activate()

        // ESP32 sends ACK,2 when Android was waiting for ACK,1
        synchronizer.onNotificationReceived("ACK,2")

        val result = synchronizer.waitForAck(expectedSequence = 1, timeoutMs = 1000L)
        assertTrue("Result should be SequenceMismatch", result is AckWaitResult.SequenceMismatch)
        val mismatch = result as AckWaitResult.SequenceMismatch
        assertEquals(1, mismatch.expected)
        assertEquals(2, mismatch.actual)
        assertEquals("ACK,2", mismatch.raw)

        synchronizer.deactivate()
    }

    @Test
    fun testDeviceErrorDetection() = runTest {
        val synchronizer = AckSynchronizer(tag = "TestSync")
        synchronizer.activate()

        synchronizer.onNotificationReceived("ERROR,0/4")

        val result = synchronizer.waitForAck(expectedSequence = 0, timeoutMs = 1000L)
        assertTrue("Result should be Error", result is AckWaitResult.Error)
        val error = result as AckWaitResult.Error
        assertEquals("ERROR,0/4", error.message)

        synchronizer.deactivate()
    }

    @Test
    fun testTimeoutHandling() = runTest {
        val synchronizer = AckSynchronizer(tag = "TestSync")
        synchronizer.activate()

        // No notification sent -> must time out cleanly
        val result = synchronizer.waitForAck(expectedSequence = 0, timeoutMs = 100L)
        assertTrue("Result should be Timeout", result is AckWaitResult.Timeout)
        val timeout = result as AckWaitResult.Timeout
        assertEquals(0, timeout.expectedSequence)

        synchronizer.deactivate()
    }

    @Test
    fun testNonAckStatusNotificationsIgnoredWhileWaitingForAck() = runTest {
        val synchronizer = AckSynchronizer(tag = "TestSync")
        synchronizer.activate()

        // Some intermediate status arrives before the actual ACK
        synchronizer.onNotificationReceived("RECEIVING,20/62")
        synchronizer.onNotificationReceived("ACK,0")

        val result = synchronizer.waitForAck(expectedSequence = 0, timeoutMs = 1000L)
        assertTrue("Result should be Success", result is AckWaitResult.Success)
        val success = result as AckWaitResult.Success
        assertEquals(0, success.sequence)

        synchronizer.deactivate()
    }

    @Test
    fun testSequentialPacketsAckFlow() = runTest {
        val synchronizer = AckSynchronizer(tag = "TestSync")
        synchronizer.activate()

        // Simulate complete 4-packet transfer sequence
        for (seq in 0..3) {
            // Write happens -> ESP32 responds
            synchronizer.onNotificationReceived("ACK,$seq")
            val result = synchronizer.waitForAck(expectedSequence = seq, timeoutMs = 1000L)
            assertTrue("Packet $seq must be acknowledged", result is AckWaitResult.Success)
            assertEquals(seq, (result as AckWaitResult.Success).sequence)
        }

        synchronizer.deactivate()
    }

    @Test
    fun staleGenerationCannotDeactivateNewOwner() = runTest {
        val synchronizer = AckSynchronizer(tag = "TestSync")
        synchronizer.activate(generation = 1L)
        synchronizer.activate(generation = 2L)

        synchronizer.deactivate(generation = 1L)
        assertTrue(synchronizer.isActive)

        synchronizer.onNotificationReceived("ACK,2")
        val result = synchronizer.waitForAck(expectedSequence = 2, timeoutMs = 1000L)
        assertTrue(result is AckWaitResult.Success)

        synchronizer.deactivate(generation = 2L)
    }
}
