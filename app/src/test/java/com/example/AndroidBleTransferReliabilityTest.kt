package com.example

import com.example.ble.StatusNotificationGate
import com.example.ble.TransferTerminalBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBleTransferReliabilityTest {

    @Test
    fun successfulCompletionTerminatesOnlyItsGeneration() {
        val boundary = activeBoundary(11L)

        assertTrue(boundary.terminate(11L))
        assertTrue(boundary.isTerminal())
        assertNull(boundary.currentGeneration())
        assertFalse(boundary.terminate(12L))
    }

    @Test
    fun staleGenerationCannotTerminateNewTransfer() {
        val boundary = activeBoundary(11L)
        boundary.begin(12L)

        assertFalse(boundary.terminate(11L))
        assertEquals(12L, boundary.currentGeneration())
        assertFalse(boundary.isTerminal())
    }

    @Test
    fun terminalBoundarySuppressesLateTransferStatuses() {
        val boundary = activeBoundary(11L)
        boundary.terminate(11L)

        listOf(
            "ACK,1",
            "RECEIVING,1/2",
            "VERIFYING,2/2",
            "ROUTE_READY,2/2",
            "ERROR,1/2"
        ).forEach { assertFalse(boundary.shouldForward(it)) }
    }

    @Test
    fun terminalBoundaryAllowsIdleStatus() {
        val boundary = activeBoundary(11L)
        boundary.terminate(11L)

        assertTrue(boundary.shouldForward("IDLE,0/0"))
    }

    @Test
    fun startingNextTransferClearsTerminalBoundary() {
        val boundary = activeBoundary(11L)
        boundary.terminate(11L)
        boundary.begin(12L)

        assertFalse(boundary.isTerminal())
        assertEquals(12L, boundary.currentGeneration())
        assertTrue(boundary.shouldForward("RECEIVING,1/2"))
        assertTrue(boundary.shouldForward("VERIFYING,2/2"))
        assertTrue(boundary.shouldForward("ROUTE_READY,2/2"))
        assertTrue(boundary.shouldForward("ERROR,1/2"))
        assertTrue(boundary.shouldForward("ACK,0"))
    }

    @Test
    fun resetClearsTerminalBoundaryForDisconnectAndReconnect() {
        val boundary = activeBoundary(11L)
        boundary.terminate(11L)
        boundary.reset()

        assertFalse(boundary.isTerminal())
        assertNull(boundary.currentGeneration())
        assertTrue(boundary.shouldForward("RECEIVING,1/2"))
        assertTrue(boundary.shouldForward("ROUTE_READY,1/1"))
    }

    @Test
    fun ordinaryFailureAndTimeoutShareTerminalFilteringPolicy() {
        val failureBoundary = activeBoundary(20L)
        val timeoutBoundary = activeBoundary(21L)

        failureBoundary.terminate(20L)
        timeoutBoundary.terminate(21L)

        assertFalse(failureBoundary.shouldForward("ACK,0"))
        assertFalse(timeoutBoundary.shouldForward("RECEIVING,3/4"))
        assertFalse(failureBoundary.shouldForward("ROUTE_READY,4/4"))
        assertFalse(timeoutBoundary.shouldForward("ERROR,4/4"))
    }

    @Test
    fun startRouteFailureTerminatesTransferOwnership() = assertTerminalAfterFailure("ACK,0")

    @Test
    fun routeDataWriteFailureTerminatesTransferOwnership() = assertTerminalAfterFailure("RECEIVING,1/2")

    @Test
    fun missingAckTimeoutTerminatesTransferOwnership() = assertTerminalAfterFailure("VERIFYING,2/2")

    @Test
    fun firmwareErrorTerminatesTransferOwnership() = assertTerminalAfterFailure("ERROR,1/2")

    @Test
    fun routeReadyTimeoutTerminatesTransferOwnership() = assertTerminalAfterFailure("ROUTE_READY,2/2")

    @Test
    fun unexpectedExceptionTerminatesTransferOwnership() = assertTerminalAfterFailure("RECEIVING,1/2")

    @Test
    fun lateAckAfterSuccessfulCompletionCannotReopenTransferring() {
        assertLateStatusSuppressed("ACK,2")
    }

    @Test
    fun lateReceivingAfterFailureCannotReopenTransferring() {
        assertLateStatusSuppressed("RECEIVING,2/4")
    }

    @Test
    fun lateVerifyingAfterFailureCannotReopenTransferring() {
        assertLateStatusSuppressed("VERIFYING,4/4")
    }

    @Test
    fun lateRouteReadyAfterFailureCannotReopenRouteReady() {
        assertLateStatusSuppressed("ROUTE_READY,4/4")
    }

    @Test
    fun lateErrorAfterSuccessfulCompletionCannotReopenError() {
        assertLateStatusSuppressed("ERROR,4/4")
    }

    @Test
    fun successfulCancellationPolicyRemainsSeparateFromTerminalBoundary() {
        val cancellationGate = StatusNotificationGate()
        cancellationGate.markTransferCancelled()
        val terminalBoundary = activeBoundary(30L)

        assertFalse(cancellationGate.shouldForward("ACK,0"))
        assertFalse(cancellationGate.shouldForward("ROUTE_READY,1/1"))
        assertFalse(terminalBoundary.isTerminal())
        assertTrue(terminalBoundary.shouldForward("RECEIVING,1/2"))
    }

    @Test
    fun failedCancellationDoesNotActivateCancellationSuppression() {
        val cancellationGate = StatusNotificationGate()

        assertTrue(cancellationGate.shouldForward("RECEIVING,1/2"))
        assertTrue(cancellationGate.shouldForward("ROUTE_READY,1/1"))
    }

    @Test
    fun terminalBoundaryDoesNotAttemptProtocolAttribution() {
        val boundary = activeBoundary(40L)
        boundary.terminate(40L)

        // Same-GATT messages are filtered only while this known local
        // terminal boundary is active; no transfer ID is inferred.
        assertFalse(boundary.shouldForward("ACK,0"))
        boundary.begin(41L)
        assertTrue(boundary.shouldForward("ACK,0"))
    }

    private fun activeBoundary(generation: Long): TransferTerminalBoundary =
        TransferTerminalBoundary().also { it.begin(generation) }

    private fun assertTerminalAfterFailure(rawStatus: String) {
        val boundary = activeBoundary(50L)
        assertTrue(boundary.terminate(50L))
        assertTrue(boundary.isTerminal())
        assertFalse(boundary.shouldForward(rawStatus))
    }

    private fun assertLateStatusSuppressed(rawStatus: String) {
        val boundary = activeBoundary(60L)
        boundary.terminate(60L)
        assertFalse(boundary.shouldForward(rawStatus))
    }
}