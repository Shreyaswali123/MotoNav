package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ble.AndroidBleRepository
import com.example.ble.TestRouteFixture
import com.example.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidBleMtuTest {

    private fun createRepository(): AndroidBleRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AndroidBleRepository(context)
    }

    @Test
    fun defaultAttMtu23RejectsCurrentRouteDataValue() {
        assertFalse(AndroidBleRepository.isRouteDataMtuSufficient(23))
    }

    @Test
    fun attMtu32RejectsCurrentRouteDataValue() {
        assertFalse(AndroidBleRepository.isRouteDataMtuSufficient(32))
    }

    @Test
    fun attMtu33AcceptsCurrentRouteDataValue() {
        assertTrue(AndroidBleRepository.isRouteDataMtuSufficient(33))
    }

    @Test
    fun largerAttMtuAcceptsCurrentRouteDataValue() {
        assertTrue(AndroidBleRepository.isRouteDataMtuSufficient(247))
    }

    @Test
    fun routeTransferRequestsMinimumRequiredAttMtu() {
        assertTrue(AndroidBleRepository.REQUESTED_ROUTE_ATT_MTU >= 33)
        assertTrue(AndroidBleRepository.ROUTE_DATA_VALUE_SIZE == 30)
    }

    @Test
    fun missingNegotiatedMtuRejectsCurrentRouteDataValue() {
        assertFalse(AndroidBleRepository.isRouteDataMtuSufficient(null))
    }

    @Test
    fun testEmptyBinaryIsRejected() {
        val repo = createRepository()
        repo.transferSerializedRoute(ByteArray(0), 0L)

        assertNotNull(repo.lastError.value)
        assertTrue(repo.lastError.value!!.contains("below minimum header size (9 bytes)"))
        assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
        assertEquals(0, repo.transferProgress.value.totalPackets)
    }

    @Test
    fun testOneByteBinaryIsRejected() {
        val repo = createRepository()
        repo.transferSerializedRoute(ByteArray(1), 0L)

        assertNotNull(repo.lastError.value)
        assertTrue(repo.lastError.value!!.contains("below minimum header size (9 bytes)"))
        assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
        assertEquals(0, repo.transferProgress.value.totalPackets)
    }

    @Test
    fun testEightByteBinaryIsRejected() {
        val repo = createRepository()
        repo.transferSerializedRoute(ByteArray(8), 0L)

        assertNotNull(repo.lastError.value)
        assertTrue(repo.lastError.value!!.contains("below minimum header size (9 bytes)"))
        assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
        assertEquals(0, repo.transferProgress.value.totalPackets)
    }

    @Test
    fun testNineByteBinaryPassesMinimumSizeValidation() {
        val repo = createRepository()
        val binary = ByteArray(9) { 0x01 }
        val crc = TestRouteFixture.calculateCrc32(binary)

        repo.transferSerializedRoute(binary, crc)

        assertNotNull(repo.lastError.value)
        assertFalse(repo.lastError.value!!.contains("below minimum header size"))
        assertFalse(repo.lastError.value!!.contains("CRC"))
        assertTrue(repo.lastError.value!!.contains("MotoNav-01 is not connected"))
    }

    @Test
    fun testCorrectCrcIsAccepted() {
        val repo = createRepository()
        val binary = ByteArray(9) { 0x42 }
        val correctCrc = TestRouteFixture.calculateCrc32(binary)

        repo.transferSerializedRoute(binary, correctCrc)

        assertNotNull(repo.lastError.value)
        assertFalse(repo.lastError.value!!.contains("CRC"))
        assertTrue(repo.lastError.value!!.contains("MotoNav-01 is not connected"))
    }

    @Test
    fun testIncorrectCrcIsRejected() {
        val repo = createRepository()
        val binary = ByteArray(9) { 0x42 }
        val correctCrc = TestRouteFixture.calculateCrc32(binary)
        val incorrectCrc = if (correctCrc == 0L) 1L else correctCrc - 1L

        repo.transferSerializedRoute(binary, incorrectCrc)

        assertNotNull(repo.lastError.value)
        assertTrue(repo.lastError.value!!.contains("CRC mismatch"))
        assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
    }

    @Test
    fun testNegativeCrcIsRejected() {
        val repo = createRepository()
        val binary = ByteArray(9) { 0x42 }

        repo.transferSerializedRoute(binary, -1L)

        assertNotNull(repo.lastError.value)
        assertTrue(repo.lastError.value!!.contains("outside valid 32-bit unsigned range"))
        assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
    }

    @Test
    fun testCrcGreaterThan32BitMaxIsRejected() {
        val repo = createRepository()
        val binary = ByteArray(9) { 0x42 }

        repo.transferSerializedRoute(binary, 0x100000000L)

        assertNotNull(repo.lastError.value)
        assertTrue(repo.lastError.value!!.contains("outside valid 32-bit unsigned range"))
        assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
    }

    @Test
    fun testIncorrectCrcCausesFailureBeforeStartRoute() {
        val repo = createRepository()
        val binary = ByteArray(9) { 0x42 }
        val wrongCrc = 0xDEADBEEFL

        repo.transferSerializedRoute(binary, wrongCrc)

        assertNotNull(repo.lastError.value)
        assertTrue(repo.lastError.value!!.contains("CRC mismatch"))
        assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
        assertEquals(0, repo.transferProgress.value.totalPackets)
        assertEquals(0, repo.transferProgress.value.percentage)
    }

    @Test
    fun testCrcMismatchDoesNotSendRouteDataPackets() {
        val repo = createRepository()
        val binary = ByteArray(62) { 0x01 }
        val wrongCrc = 0x12345678L

        repo.transferSerializedRoute(binary, wrongCrc)

        assertNotNull(repo.lastError.value)
        assertTrue(repo.lastError.value!!.contains("CRC mismatch"))
        assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
        assertEquals(0, repo.transferProgress.value.currentPacket)
        assertEquals(0, repo.transferProgress.value.totalPackets)
    }
}