package com.example

import com.example.ble.StatusNotificationGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBleStatusNotificationTest {

    @Test
    fun postCancellationRouteReadyIsSuppressed() {
        val gate = canceledGate()

        assertFalse(gate.shouldForward("ROUTE_READY,1/1"))
    }

    @Test
    fun postCancellationErrorIsSuppressed() {
        val gate = canceledGate()

        assertFalse(gate.shouldForward("ERROR,1/1"))
    }

    @Test
    fun postCancellationReceivingIsSuppressed() {
        val gate = canceledGate()

        assertFalse(gate.shouldForward("RECEIVING,5/10"))
    }

    @Test
    fun postCancellationVerifyingIsSuppressed() {
        val gate = canceledGate()

        assertFalse(gate.shouldForward("VERIFYING,10/10"))
    }

    @Test
    fun postCancellationAckIsSuppressed() {
        val gate = canceledGate()

        assertFalse(gate.shouldForward("ACK,2"))
    }

    @Test
    fun postCancellationIdleRemainsAllowed() {
        val gate = canceledGate()

        assertTrue(gate.shouldForward("IDLE,0/0"))
    }

    @Test
    fun activeTransferReceivingRemainsAllowed() {
        val gate = activeTransferGate()

        assertTrue(gate.shouldForward("RECEIVING,5/10"))
    }

    @Test
    fun activeTransferVerifyingRemainsAllowed() {
        val gate = activeTransferGate()

        assertTrue(gate.shouldForward("VERIFYING,10/10"))
    }

    @Test
    fun activeTransferRouteReadyRemainsAllowed() {
        val gate = activeTransferGate()

        assertTrue(gate.shouldForward("ROUTE_READY,1/1"))
    }

    @Test
    fun activeTransferErrorRemainsAllowed() {
        val gate = activeTransferGate()

        assertTrue(gate.shouldForward("ERROR,1/1"))
    }

    @Test
    fun activeTransferAckRemainsAllowed() {
        val gate = activeTransferGate()

        assertTrue(gate.shouldForward("ACK,2"))
    }

    @Test
    fun cancellationBoundarySuppressesAllTransferSpecificStatusesBeforeDistribution() {
        val gate = canceledGate()

        listOf(
            "RECEIVING,1/2",
            "VERIFYING,2/2",
            "ROUTE_READY,2/2",
            "ERROR,1/2",
            "ACK,1"
        ).forEach { assertFalse(gate.shouldForward(it)) }
    }

    @Test
    fun startingNextTransferClearsCancellationBoundary() {
        val gate = canceledGate()

        gate.markTransferStarted()

        assertTrue(gate.shouldForward("RECEIVING,1/2"))
        assertTrue(gate.shouldForward("VERIFYING,2/2"))
        assertTrue(gate.shouldForward("ROUTE_READY,2/2"))
    }

    @Test
    fun nextTransferErrorRemainsAllowedAfterCancellation() {
        val gate = canceledGate()

        gate.markTransferStarted()

        assertTrue(gate.shouldForward("ERROR,1/2"))
    }

    @Test
    fun nextTransferAckRemainsAllowedAfterCancellation() {
        val gate = canceledGate()

        gate.markTransferStarted()

        assertTrue(gate.shouldForward("ACK,0"))
    }

    @Test
    fun disconnectClearsCancellationBoundary() {
        val gate = canceledGate()

        gate.resetConnection()

        assertTrue(gate.shouldForward("RECEIVING,1/2"))
        assertTrue(gate.shouldForward("ROUTE_READY,1/1"))
    }

    @Test
    fun gateDoesNotChangeNormalIdleBehavior() {
        val gate = StatusNotificationGate()

        assertTrue(gate.shouldForward("IDLE,0/0"))
    }

    @Test
    fun gateStartsWithoutPostCancellationSuppression() {
        val gate = StatusNotificationGate()

        assertFalse(gate.isPostCancellationBoundaryActive())
        gate.markTransferCancelled()
        assertTrue(gate.isPostCancellationBoundaryActive())
        gate.markTransferStarted()
        assertFalse(gate.isPostCancellationBoundaryActive())
    }

    private fun canceledGate(): StatusNotificationGate =
        StatusNotificationGate().also { it.markTransferCancelled() }

    private fun activeTransferGate(): StatusNotificationGate =
        StatusNotificationGate().also { it.markTransferStarted() }
}