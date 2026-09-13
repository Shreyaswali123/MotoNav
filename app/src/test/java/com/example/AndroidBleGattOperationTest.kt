package com.example

import com.example.ble.GattOperationInvalidatedException
import com.example.ble.GattOperationKind
import com.example.ble.GattOperationQueue
import com.example.ble.GattOperationResult
import com.example.ble.GattOperationTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidBleGattOperationTest {

    private val characteristicUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val descriptorUuid = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun operationsStartStrictlyInFifoOrder() = runTest {
        val started = mutableListOf<GattOperationKind>()
        val queue = GattOperationQueue(Any(), 1L, this, onTimeout = {})

        val first = async {
            queue.execute(GattOperationKind.MTU) {
                started += GattOperationKind.MTU
                true
            }
        }
        val second = async {
            queue.execute(GattOperationKind.SERVICE_DISCOVERY) {
                started += GattOperationKind.SERVICE_DISCOVERY
                true
            }
        }
        runCurrent()

        assertEquals(listOf(GattOperationKind.MTU), started)
        assertTrue(queue.complete(GattOperationKind.MTU, result = GattOperationResult(0, mtu = 33)))
        runCurrent()
        assertEquals(
            listOf(GattOperationKind.MTU, GattOperationKind.SERVICE_DISCOVERY),
            started
        )
        assertTrue(queue.complete(GattOperationKind.SERVICE_DISCOVERY, result = GattOperationResult(0)))
        assertEquals(33, first.await().mtu)
        second.await()
    }

    @Test
    fun statusReadWaitsForDescriptorWrite() = runTest {
        val started = mutableListOf<GattOperationKind>()
        val descriptor = Any()
        val characteristic = Any()
        val queue = GattOperationQueue(Any(), 1L, this, onTimeout = {})

        val descriptorWrite = async {
            queue.execute(
                kind = GattOperationKind.DESCRIPTOR_WRITE,
                targetIdentity = descriptor,
                targetUuid = descriptorUuid
            ) {
                started += GattOperationKind.DESCRIPTOR_WRITE
                true
            }
        }
        val read = async {
            queue.execute(
                kind = GattOperationKind.CHARACTERISTIC_READ,
                targetIdentity = characteristic,
                targetUuid = characteristicUuid
            ) {
                started += GattOperationKind.CHARACTERISTIC_READ
                true
            }
        }
        runCurrent()

        assertEquals(listOf(GattOperationKind.DESCRIPTOR_WRITE), started)
        assertFalse(queue.complete(
            GattOperationKind.CHARACTERISTIC_READ,
            targetIdentity = characteristic,
            targetUuid = characteristicUuid,
            result = GattOperationResult(0)
        ))
        assertTrue(queue.complete(
            GattOperationKind.DESCRIPTOR_WRITE,
            targetIdentity = descriptor,
            targetUuid = descriptorUuid,
            result = GattOperationResult(0)
        ))
        runCurrent()
        assertEquals(
            listOf(GattOperationKind.DESCRIPTOR_WRITE, GattOperationKind.CHARACTERISTIC_READ),
            started
        )
        assertTrue(queue.complete(
            GattOperationKind.CHARACTERISTIC_READ,
            targetIdentity = characteristic,
            targetUuid = characteristicUuid,
            result = GattOperationResult(0)
        ))
        descriptorWrite.await()
        read.await()
    }

    @Test
    fun rssiReadWaitsForAnotherGattOperation() = runTest {
        val started = mutableListOf<GattOperationKind>()
        val queue = GattOperationQueue(Any(), 1L, this, onTimeout = {})
        val mtu = async {
            queue.execute(GattOperationKind.MTU) {
                started += GattOperationKind.MTU
                true
            }
        }
        val rssi = async {
            queue.execute(GattOperationKind.RSSI) {
                started += GattOperationKind.RSSI
                true
            }
        }
        runCurrent()
        assertEquals(listOf(GattOperationKind.MTU), started)
        queue.complete(GattOperationKind.MTU, result = GattOperationResult(0, mtu = 33))
        runCurrent()
        assertEquals(listOf(GattOperationKind.MTU, GattOperationKind.RSSI), started)
        queue.complete(GattOperationKind.RSSI, result = GattOperationResult(0, rssi = -55))
        assertEquals(-55, rssi.await().rssi)
        mtu.await()
    }

    @Test
    fun multipleStatusReadsAreSerialized() = runTest {
        var starts = 0
        val queue = GattOperationQueue(Any(), 1L, this, onTimeout = {})
        val characteristic = Any()
        val first = async {
            queue.execute(GattOperationKind.CHARACTERISTIC_READ, characteristic, characteristicUuid) {
                starts++
                true
            }
        }
        val second = async {
            queue.execute(GattOperationKind.CHARACTERISTIC_READ, characteristic, characteristicUuid) {
                starts++
                true
            }
        }
        runCurrent()
        assertEquals(1, starts)
        queue.complete(
            GattOperationKind.CHARACTERISTIC_READ,
            characteristic,
            characteristicUuid,
            GattOperationResult(0)
        )
        runCurrent()
        assertEquals(2, starts)
        queue.complete(
            GattOperationKind.CHARACTERISTIC_READ,
            characteristic,
            characteristicUuid,
            GattOperationResult(0)
        )
        first.await()
        second.await()
    }

    @Test
    fun wrongCharacteristicCallbackDoesNotCompletePendingWrite() = runTest {
        val expected = Any()
        val wrong = Any()
        val queue = GattOperationQueue(Any(), 1L, this, onTimeout = {})
        val write = async {
            queue.execute(
                kind = GattOperationKind.CHARACTERISTIC_WRITE,
                targetIdentity = expected,
                targetUuid = characteristicUuid
            ) { true }
        }
        runCurrent()

        assertFalse(queue.complete(
            GattOperationKind.CHARACTERISTIC_WRITE,
            targetIdentity = wrong,
            targetUuid = characteristicUuid,
            result = GattOperationResult(0)
        ))
        assertFalse(write.isCompleted)
        assertTrue(queue.complete(
            GattOperationKind.CHARACTERISTIC_WRITE,
            targetIdentity = expected,
            targetUuid = characteristicUuid,
            result = GattOperationResult(0)
        ))
        write.await()
    }

    @Test
    fun wrongDescriptorCallbackDoesNotCompletePendingDescriptorWrite() = runTest {
        val expected = Any()
        val wrong = Any()
        val queue = GattOperationQueue(Any(), 1L, this, onTimeout = {})
        val write = async {
            queue.execute(
                kind = GattOperationKind.DESCRIPTOR_WRITE,
                targetIdentity = expected,
                targetUuid = descriptorUuid
            ) { true }
        }
        runCurrent()

        assertFalse(queue.complete(
            GattOperationKind.DESCRIPTOR_WRITE,
            targetIdentity = wrong,
            targetUuid = descriptorUuid,
            result = GattOperationResult(0)
        ))
        assertFalse(write.isCompleted)
        assertTrue(queue.complete(
            GattOperationKind.DESCRIPTOR_WRITE,
            targetIdentity = expected,
            targetUuid = descriptorUuid,
            result = GattOperationResult(0)
        ))
        write.await()
    }

    @Test
    fun staleGattCallbackIsIgnoredByQueueOwnership() = runTest {
        val activeGatt = Any()
        val staleGatt = Any()
        val queue = GattOperationQueue(activeGatt, 1L, this, onTimeout = {})
        supervisorScope {
            val operation = async {
                queue.execute(GattOperationKind.MTU) { true }
            }
            runCurrent()

            assertFalse(queue.complete(
                kind = GattOperationKind.MTU,
                result = GattOperationResult(0, mtu = 33),
                callbackGattIdentity = staleGatt
            ))
            assertFalse(operation.isCompleted)
            queue.invalidate()
            assertFailsWith<GattOperationInvalidatedException> {
                operation.await()
            }
        }
    }

    @Test
    fun aNewEpochStartsWithAnIndependentCleanQueue() = runTest {
        val oldQueue = GattOperationQueue(Any(), 1L, this, onTimeout = {})
        supervisorScope {
            val oldOperation = async { oldQueue.execute(GattOperationKind.MTU) { true } }
            runCurrent()
            oldQueue.invalidate()
            assertFailsWith<GattOperationInvalidatedException> {
                oldOperation.await()
            }
        }

        var newStarted = false
        val newQueue = GattOperationQueue(Any(), 2L, this, onTimeout = {})
        val newOperation = async {
            newQueue.execute(GattOperationKind.MTU) {
                newStarted = true
                true
            }
        }
        runCurrent()
        assertTrue(newStarted)
        newQueue.complete(GattOperationKind.MTU, result = GattOperationResult(0, mtu = 33))
        newOperation.await()
        assertFalse(oldQueue.complete(GattOperationKind.MTU, result = GattOperationResult(0)))
    }

    @Test
    fun disconnectInvalidatesActiveAndQueuedOperations() = runTest {
        val queue = GattOperationQueue(Any(), 1L, this, onTimeout = {})
        supervisorScope {
            val active = async { queue.execute(GattOperationKind.MTU) { true } }
            val queued = async { queue.execute(GattOperationKind.RSSI) { true } }
            runCurrent()

            queue.invalidate(GattOperationInvalidatedException("disconnect"))
            assertFailsWith<GattOperationInvalidatedException> {
                active.await()
            }
            assertFailsWith<GattOperationInvalidatedException> {
                queued.await()
            }
        }
        assertFalse(queue.complete(GattOperationKind.MTU, result = GattOperationResult(0)))
    }

    @Test
    fun timeoutPoisonsQueueAndStartsNoNewOperation() = runTest {
        var timeoutCount = 0
        var secondStarted = false
        val queue = GattOperationQueue(
            gattIdentity = Any(),
            epoch = 1L,
            scope = this,
            onTimeout = { timeoutCount++ }
        )
        supervisorScope {
            val timedOut = async {
                queue.execute(GattOperationKind.CHARACTERISTIC_WRITE, timeoutMs = 10L) { true }
            }
            val queued = async {
                queue.execute(GattOperationKind.CHARACTERISTIC_READ) {
                    secondStarted = true
                    true
                }
            }
            runCurrent()
            advanceTimeBy(11L)
            runCurrent()

            assertEquals(1, timeoutCount)
            assertFalse(secondStarted)
            assertFailsWith<GattOperationTimeoutException> {
                timedOut.await()
            }
            assertFailsWith<GattOperationInvalidatedException> {
                queued.await()
            }
        }
        assertFalse(queue.complete(GattOperationKind.CHARACTERISTIC_WRITE, result = GattOperationResult(0)))
    }

    @Test
    fun queuedCancellationWriteRunsAfterActiveWriteCompletes() = runTest {
        val started = mutableListOf<String>()
        val queue = GattOperationQueue(Any(), 1L, this, onTimeout = {})
        val routeWrite = async {
            queue.execute(GattOperationKind.CHARACTERISTIC_WRITE) {
                started += "route"
                true
            }
        }
        val cancelWrite = async {
            queue.execute(GattOperationKind.CHARACTERISTIC_WRITE) {
                started += "cancel"
                true
            }
        }
        runCurrent()
        assertEquals(listOf("route"), started)
        queue.complete(GattOperationKind.CHARACTERISTIC_WRITE, result = GattOperationResult(0))
        runCurrent()
        assertEquals(listOf("route", "cancel"), started)
        queue.complete(GattOperationKind.CHARACTERISTIC_WRITE, result = GattOperationResult(0))
        routeWrite.await()
        cancelWrite.await()
    }

    private suspend inline fun <reified T : Throwable> assertFailsWith(crossinline block: suspend () -> Unit): T {
        try {
            block()
            fail("Expected ${T::class.java.simpleName} but no exception was thrown")
            throw AssertionError("Unreachable")
        } catch (e: Throwable) {
            if (e is T) {
                return e
            }
            throw e
        }
    }
}