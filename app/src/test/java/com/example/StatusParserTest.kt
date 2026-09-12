package com.example

import com.example.ble.StatusParser
import com.example.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StatusParserTest {

    @Test
    fun testParseIdle() {
        val result = StatusParser.parse("IDLE,0/0")
        assertEquals(ConnectionState.Connected, result.state)
        assertEquals(0, result.progress.percentage)
        assertEquals(0, result.progress.currentPacket)
        assertEquals(0, result.progress.totalPackets)
        assertEquals("Standby (Idle)", result.progress.stepDescription)
        assertNull(result.errorMessage)
    }

    @Test
    fun testParseReceiving() {
        val result = StatusParser.parse("RECEIVING,12/48")
        assertEquals(ConnectionState.Transferring, result.state)
        assertEquals(25, result.progress.percentage)
        assertEquals(12, result.progress.currentPacket)
        assertEquals(48, result.progress.totalPackets)
        assertEquals("Receiving route packets (12/48)...", result.progress.stepDescription)
        assertNull(result.errorMessage)
    }

    @Test
    fun testParseVerifying() {
        val result = StatusParser.parse("VERIFYING,48/48\r\n")
        assertEquals(ConnectionState.Transferring, result.state)
        assertEquals(100, result.progress.percentage)
        assertEquals(48, result.progress.currentPacket)
        assertEquals(48, result.progress.totalPackets)
        assertEquals("Verifying route on ESP32 (48/48)...", result.progress.stepDescription)
        assertNull(result.errorMessage)
    }

    @Test
    fun testParseRouteReady() {
        val result = StatusParser.parse("ROUTE_READY,48/48")
        assertEquals(ConnectionState.RouteReady, result.state)
        assertEquals(100, result.progress.percentage)
        assertEquals(48, result.progress.currentPacket)
        assertEquals(48, result.progress.totalPackets)
        assertEquals("Route synced to ESP32 cockpit HUD (48/48)", result.progress.stepDescription)
        assertNull(result.errorMessage)
    }

    @Test
    fun testParseError() {
        val result = StatusParser.parse("ERROR,3/48")
        assertEquals(ConnectionState.Error, result.state)
        assertEquals(6, result.progress.percentage)
        assertEquals(3, result.progress.currentPacket)
        assertEquals(48, result.progress.totalPackets)
        assertNotNull(result.errorMessage)
    }

    // 1. Unknown Statuses
    @Test
    fun testUnknownStatusesProduceError() {
        val inputs = listOf(
            "RESET",
            "FAULT",
            "UNKNOWN",
            "GARBLED",
            "",
            "   ",
            "FOO,BAR,BAZ",
            "FOO,BAR"
        )
        for (input in inputs) {
            val result = StatusParser.parse(input)
            assertEquals("Expected Error for unknown status: '$input'", ConnectionState.Error, result.state)
            assertNotNull("Expected non-null errorMessage for '$input'", result.errorMessage)
            assertEquals("Expected percentage=0 for error '$input'", 0, result.progress.percentage)
            assertEquals("Expected currentPacket=0 for error '$input'", 0, result.progress.currentPacket)
            assertEquals("Expected totalPackets=0 for error '$input'", 0, result.progress.totalPackets)
        }
    }

    // 2. Structured Status Field Count
    @Test
    fun testStructuredStatusFieldCountEnforced() {
        val malformedFieldCountInputs = listOf(
            "RECEIVING",
            "RECEIVING,1/10,EXTRA",
            "ACK",
            "ACK,1,EXTRA",
            "IDLE,0/0,EXTRA",
            "VERIFYING,1/10,EXTRA",
            "ROUTE_READY,1/10,EXTRA",
            "ERROR,1/10,EXTRA",
            "IDLE"
        )
        for (input in malformedFieldCountInputs) {
            val result = StatusParser.parse(input)
            assertEquals("Expected Error for field count violation in '$input'", ConnectionState.Error, result.state)
            assertNotNull("Expected non-null errorMessage for '$input'", result.errorMessage)
        }
    }

    // 3. Ratio Structure Validation
    @Test
    fun testRatioStructureValidation() {
        val malformedRatioInputs = listOf(
            "RECEIVING,1/2/3",
            "RECEIVING,1//2",
            "RECEIVING,1/2/",
            "RECEIVING,/2",
            "RECEIVING,1/",
            "RECEIVING,1",
            "RECEIVING,",
            "IDLE,1/2/3",
            "VERIFYING,1//2",
            "ROUTE_READY,1/2/"
        )
        for (input in malformedRatioInputs) {
            val result = StatusParser.parse(input)
            assertEquals("Expected Error for malformed ratio structure in '$input'", ConnectionState.Error, result.state)
            assertNotNull("Expected non-null errorMessage for '$input'", result.errorMessage)
        }
    }

    // 4. Invalid Progress Invariants & Non-Numeric Fields
    @Test
    fun testInvalidProgressInvariantsProduceError() {
        val invalidProgressInputs = listOf(
            "RECEIVING,0/0",
            "RECEIVING,-1/10",
            "RECEIVING,1/-10",
            "RECEIVING,-1/-10",
            "RECEIVING,11/10",
            "RECEIVING,10/5",
            "RECEIVING,foo/10",
            "RECEIVING,10/foo",
            "RECEIVING,999999999999999999999/10",
            "RECEIVING,10/999999999999999999999",
            "RECEIVING,65536/10",
            "RECEIVING,10/65536",
            "VERIFYING,0/0",
            "VERIFYING,11/10",
            "IDLE,5/2",
            "IDLE,-1/0",
            "ROUTE_READY,5/2",
            "ERROR,10/5"
        )
        for (input in invalidProgressInputs) {
            val result = StatusParser.parse(input)
            assertEquals("Expected Error for invalid progress in '$input'", ConnectionState.Error, result.state)
            assertNotNull("Expected non-null errorMessage for '$input'", result.errorMessage)
        }
    }

    // 5. Valid Boundaries
    @Test
    fun testValidBoundariesSucceed() {
        val idleResult = StatusParser.parse("IDLE,0/0")
        assertEquals(ConnectionState.Connected, idleResult.state)
        assertEquals(0, idleResult.progress.percentage)
        assertEquals(0, idleResult.progress.currentPacket)
        assertEquals(0, idleResult.progress.totalPackets)
        assertNull(idleResult.errorMessage)

        val recStart = StatusParser.parse("RECEIVING,0/1")
        assertEquals(ConnectionState.Transferring, recStart.state)
        assertEquals(0, recStart.progress.percentage)
        assertEquals(0, recStart.progress.currentPacket)
        assertEquals(1, recStart.progress.totalPackets)
        assertNull(recStart.errorMessage)

        val recEnd = StatusParser.parse("RECEIVING,1/1")
        assertEquals(ConnectionState.Transferring, recEnd.state)
        assertEquals(100, recEnd.progress.percentage)
        assertEquals(1, recEnd.progress.currentPacket)
        assertEquals(1, recEnd.progress.totalPackets)
        assertNull(recEnd.errorMessage)

        val maxBound = StatusParser.parse("RECEIVING,65535/65535")
        assertEquals(ConnectionState.Transferring, maxBound.state)
        assertEquals(100, maxBound.progress.percentage)
        assertEquals(65535, maxBound.progress.currentPacket)
        assertEquals(65535, maxBound.progress.totalPackets)
        assertNull(maxBound.errorMessage)

        val ack0 = StatusParser.parse("ACK,0")
        assertEquals(ConnectionState.Transferring, ack0.state)
        assertEquals(0, ack0.progress.percentage)
        assertEquals(0, ack0.progress.currentPacket)
        assertEquals(0, ack0.progress.totalPackets)
        assertEquals("Packet 0 acknowledged", ack0.progress.stepDescription)
        assertNull(ack0.errorMessage)

        val ackMax = StatusParser.parse("ACK,65535")
        assertEquals(ConnectionState.Transferring, ackMax.state)
        assertEquals(0, ackMax.progress.percentage)
        assertEquals(65535, ackMax.progress.currentPacket)
        assertEquals(0, ackMax.progress.totalPackets)
        assertEquals("Packet 65535 acknowledged", ackMax.progress.stepDescription)
        assertNull(ackMax.errorMessage)
    }

    // 6. Malformed ACK Validation
    @Test
    fun testMalformedAcksProduceError() {
        val malformedAcks = listOf(
            "ACK",
            "ACK,",
            "ACK,foo",
            "ACK,-1",
            "ACK,65536",
            "ACK,1,extra",
            "ACK,,1",
            "ACK,999999999999999999999"
        )
        for (input in malformedAcks) {
            val result = StatusParser.parse(input)
            assertEquals("Expected Error for malformed ACK '$input'", ConnectionState.Error, result.state)
            assertNotNull("Expected non-null errorMessage for '$input'", result.errorMessage)
            assertEquals(0, result.progress.currentPacket)
        }
    }
}
