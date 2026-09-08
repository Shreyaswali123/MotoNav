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
}
