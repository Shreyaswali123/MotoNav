package com.example

import com.example.ble.TestRouteFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TestRouteFixtureTest {

    @Test
    fun testRouteBinaryExactSizeAndCrc() {
        val binary = TestRouteFixture.createTestRouteBinary()
        // 1. byte array length == 62
        assertEquals(62, binary.size)

        // 2. CRC32 == 0xDC0213B4
        val crc = TestRouteFixture.calculateCrc32(binary)
        val expectedCrc = 0xDC0213B4L
        assertEquals(
            "CRC32 must match expected 0xDC0213B4 (found 0x${crc.toString(16).uppercase()})",
            expectedCrc,
            crc
        )
    }

    @Test
    fun testGeneratedBytesExactlyMatchHexadecimalReference() {
        val binary = TestRouteFixture.createTestRouteBinary()
        val generatedHex = TestRouteFixture.toHexString(binary)
        val expectedHex = TestRouteFixture.HEX_REFERENCE.replace(Regex("\\s+"), " ").trim()
        val actualHexNormalized = generatedHex.replace(Regex("\\s+"), " ").trim()

        assertEquals(
            "Generated binary bytes must exactly match the canonical hexadecimal reference",
            expectedHex,
            actualHexNormalized
        )
    }

    @Test
    fun testEveryFieldUsesLittleEndianEncoding() {
        val binary = TestRouteFixture.createTestRouteBinary()
        assertEquals(62, binary.size)

        val buf = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)

        // HEADER (9 bytes):
        // byte 0: version = 1
        assertEquals(1.toByte(), buf.get())
        // bytes 1-4: route ID = uint32 LE, value 1
        assertEquals(1, buf.int)
        // bytes 5-6: point count = uint16 LE, value 4
        assertEquals(4.toShort(), buf.short)
        // bytes 7-8: maneuver count = uint16 LE, value 3
        assertEquals(3.toShort(), buf.short)

        // POINT 0 (8 bytes): lat 15.3647000, lon 75.1240000
        assertEquals(153647000, buf.int)
        assertEquals(751240000, buf.int)

        // POINT 1 (8 bytes): lat 15.3658000, lon 75.1262000
        assertEquals(153658000, buf.int)
        assertEquals(751262000, buf.int)

        // POINT 2 (8 bytes): lat 15.3681000, lon 75.1294000
        assertEquals(153681000, buf.int)
        assertEquals(751294000, buf.int)

        // POINT 3 (8 bytes): lat 15.3700000, lon 75.1320000
        assertEquals(153700000, buf.int)
        assertEquals(751320000, buf.int)

        // MANEUVER 0 (7 bytes): point index 1 (uint16 LE), type 2 (uint8), distance 250 (uint32 LE)
        assertEquals(1.toShort(), buf.short)
        assertEquals(2.toByte(), buf.get())
        assertEquals(250, buf.int)

        // MANEUVER 1 (7 bytes): point index 2 (uint16 LE), type 1 (uint8), distance 180 (uint32 LE)
        assertEquals(2.toShort(), buf.short)
        assertEquals(1.toByte(), buf.get())
        assertEquals(180, buf.int)

        // MANEUVER 2 (7 bytes): point index 3 (uint16 LE), type 5 (uint8), distance 0 (uint32 LE)
        assertEquals(3.toShort(), buf.short)
        assertEquals(5.toByte(), buf.get())
        assertEquals(0, buf.int)

        // All 62 bytes consumed
        assertEquals(62, buf.position())
    }

    @Test
    fun testStartRouteCommand() {
        val cmd = TestRouteFixture.buildStartRouteCommand(62)
        assertEquals(5, cmd.size)
        assertEquals(0x01.toByte(), cmd[0])
        val buf = ByteBuffer.wrap(cmd).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // skip opcode
        assertEquals(62, buf.int)
    }

    @Test
    fun testEndRouteCommand() {
        val cmd = TestRouteFixture.buildEndRouteCommand(0xDC0213B4L)
        assertEquals(5, cmd.size)
        assertEquals(0x02.toByte(), cmd[0])
        val buf = ByteBuffer.wrap(cmd).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // skip opcode
        val readCrc = buf.int.toLong() and 0xFFFFFFFFL
        assertEquals(0xDC0213B4L, readCrc)
    }

    @Test
    fun testPacketFramingAndCrc() {
        val packets = TestRouteFixture.createRouteDataPackets(chunkSize = 20)
        assertEquals(4, packets.size)

        // Packet 0: 20 bytes payload -> 6 + 20 + 4 = 30 bytes
        assertEquals(0, packets[0].sequence)
        assertEquals(20, packets[0].payloadLength)
        assertEquals(30, packets[0].framedBytes.size)

        // Packet 1: 20 bytes payload -> 30 bytes
        assertEquals(1, packets[1].sequence)
        assertEquals(20, packets[1].payloadLength)
        assertEquals(30, packets[1].framedBytes.size)

        // Packet 2: 20 bytes payload -> 30 bytes
        assertEquals(2, packets[2].sequence)
        assertEquals(20, packets[2].payloadLength)
        assertEquals(30, packets[2].framedBytes.size)

        // Packet 3: 2 bytes payload -> 6 + 2 + 4 = 12 bytes
        assertEquals(3, packets[3].sequence)
        assertEquals(2, packets[3].payloadLength)
        assertEquals(12, packets[3].framedBytes.size)

        // Verify total payload size equals 62 bytes
        val totalPayloadSize = packets.sumOf { it.payloadLength }
        assertEquals(62, totalPayloadSize)

        // Verify framing fields for packet 0
        val p0Bytes = packets[0].framedBytes
        val buf = ByteBuffer.wrap(p0Bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x03.toByte(), buf.get())         // type
        assertEquals(0.toShort(), buf.short)          // seq 0
        assertEquals(20.toShort(), buf.short)         // payload len
        assertEquals(0x00.toByte(), buf.get())         // flags

        val payloadExtracted = ByteArray(20)
        buf.get(payloadExtracted)
        assertTrue(payloadExtracted.contentEquals(packets[0].payload))

        val packetCrcFromBuf = buf.int.toLong() and 0xFFFFFFFFL
        val calculatedPacketCrc = TestRouteFixture.calculateCrc32(p0Bytes, 0, 26)
        assertEquals(calculatedPacketCrc, packetCrcFromBuf)
    }

    @Test
    fun testStatusParserHandlesAckAndRouteReady() {
        val ack0 = com.example.ble.StatusParser.parse("ACK,0")
        assertEquals(com.example.model.ConnectionState.Transferring, ack0.state)
        assertEquals("ACK,0", ack0.rawStatus)

        val ack3 = com.example.ble.StatusParser.parse("ACK,3")
        assertEquals(com.example.model.ConnectionState.Transferring, ack3.state)

        val routeReady = com.example.ble.StatusParser.parse("ROUTE_READY,62/62")
        assertEquals(com.example.model.ConnectionState.RouteReady, routeReady.state)
        assertEquals(62, routeReady.progress.currentPacket)
        assertEquals(62, routeReady.progress.totalPackets)
        assertEquals(100, routeReady.progress.percentage)
    }

    @Test
    fun testMockBleRepositorySendTestRoute() = kotlinx.coroutines.runBlocking {
        val mockRepo = com.example.ble.MockBleRepository(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        mockRepo.connect("MotoNav-01")

        // Wait until connected
        var connected = false
        for (i in 0..50) {
            if (mockRepo.connectionState.value == com.example.model.ConnectionState.Connected) {
                connected = true
                break
            }
            kotlinx.coroutines.delay(50)
        }
        assertTrue("Device should connect", connected)

        // Run sendTestRoute
        mockRepo.sendTestRoute()

        // Wait until RouteReady
        var routeReady = false
        for (i in 0..100) {
            if (mockRepo.connectionState.value == com.example.model.ConnectionState.RouteReady) {
                routeReady = true
                break
            }
            kotlinx.coroutines.delay(50)
        }
        assertTrue("Route transfer should complete and reach RouteReady", routeReady)
        assertEquals("ROUTE_READY,62/62", mockRepo.connectedDevice.value?.rawStatus)
    }

    @Test
    fun testMockBleRepositoryTransferSerializedRoute() = kotlinx.coroutines.runBlocking {
        val mockRepo = com.example.ble.MockBleRepository(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        mockRepo.connect("MotoNav-01")

        // Wait until connected
        var connected = false
        for (i in 0..50) {
            if (mockRepo.connectionState.value == com.example.model.ConnectionState.Connected) {
                connected = true
                break
            }
            kotlinx.coroutines.delay(50)
        }
        assertTrue("Device should connect", connected)

        val testPayload = ByteArray(100) { it.toByte() }
        val testCrc = com.example.ble.TestRouteFixture.calculateCrc32(testPayload)

        mockRepo.transferSerializedRoute(testPayload, testCrc)

        // Wait until RouteReady
        var routeReady = false
        for (i in 0..100) {
            if (mockRepo.connectionState.value == com.example.model.ConnectionState.RouteReady) {
                routeReady = true
                break
            }
            kotlinx.coroutines.delay(50)
        }
        assertTrue("Dynamic route transfer should complete and reach RouteReady", routeReady)
        assertEquals("ROUTE_READY,100/100", mockRepo.connectedDevice.value?.rawStatus)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateRouteDataPacketsChunkSizeZeroFails() {
        TestRouteFixture.createRouteDataPackets(chunkSize = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCreateRouteDataPacketsNegativeChunkSizeFails() {
        TestRouteFixture.createRouteDataPackets(chunkSize = -1)
    }

    @Test
    fun testFrameRouteDataPacketSequenceZeroSucceeds() {
        val payload = byteArrayOf(0x01, 0x02)
        val packet = TestRouteFixture.frameRouteDataPacket(0, payload)
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x03.toByte(), buf.get()) // type
        val seq = buf.short.toInt() and 0xFFFF
        assertEquals(0, seq)
    }

    @Test
    fun testFrameRouteDataPacketSequence65535Succeeds() {
        val payload = byteArrayOf(0x01, 0x02)
        val packet = TestRouteFixture.frameRouteDataPacket(65535, payload)
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x03.toByte(), buf.get()) // type
        val seq = buf.short.toInt() and 0xFFFF
        assertEquals(65535, seq)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFrameRouteDataPacketSequence65536Fails() {
        TestRouteFixture.frameRouteDataPacket(65536, byteArrayOf(0x01))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFrameRouteDataPacketNegativeSequenceFails() {
        TestRouteFixture.frameRouteDataPacket(-1, byteArrayOf(0x01))
    }

    @Test
    fun testFrameRouteDataPacketPayloadLength65535Succeeds() {
        val largePayload = ByteArray(65535)
        val packet = TestRouteFixture.frameRouteDataPacket(1, largePayload)
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x03.toByte(), buf.get()) // type
        buf.short // seq
        val len = buf.short.toInt() and 0xFFFF
        assertEquals(65535, len)
        assertEquals(6 + 65535 + 4, packet.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFrameRouteDataPacketPayloadLengthGreaterThan65535Fails() {
        val oversizedPayload = ByteArray(65536)
        TestRouteFixture.frameRouteDataPacket(1, oversizedPayload)
    }

    @Test
    fun testBuildStartRouteCommandExpectedSizeZeroSucceeds() {
        val cmd = TestRouteFixture.buildStartRouteCommand(0)
        assertEquals(5, cmd.size)
        assertEquals(0x01.toByte(), cmd[0])
        val buf = ByteBuffer.wrap(cmd).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // opcode
        assertEquals(0, buf.int)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBuildStartRouteCommandNegativeExpectedSizeFails() {
        TestRouteFixture.buildStartRouteCommand(-1)
    }
}
