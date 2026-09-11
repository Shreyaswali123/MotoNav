package com.example

import com.example.ble.RouteReadyEventTracker
import com.example.ble.RouteVerificationEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteReadyEventTrackerTest {

    @Test
    fun previousRouteReadyIsNotUsedByNewTransfer() = runTest {
        val tracker = RouteReadyEventTracker()
        val firstGeneration = tracker.beginTransfer()
        tracker.beginVerification(firstGeneration)
        tracker.onStatus("ROUTE_READY,62/62")

        val secondGeneration = tracker.beginTransfer()
        tracker.beginVerification(secondGeneration)
        val result = async { tracker.awaitVerificationEvent(secondGeneration, 100L) }
        advanceUntilIdle()

        assertFalse(result.await() is RouteVerificationEvent.Ready)
    }

    @Test
    fun freshRouteReadyCompletesCurrentTransfer() = runTest {
        val tracker = RouteReadyEventTracker()
        val generation = tracker.beginTransfer()
        tracker.beginVerification(generation)

        val result = async { tracker.awaitVerificationEvent(generation, 1000L) }
        tracker.onStatus("ROUTE_READY,448/448")

        assertEquals(
            RouteVerificationEvent.Ready("ROUTE_READY,448/448"),
            result.await()
        )
    }

    @Test
    fun delayedCurrentTransferRouteReadyCompletes() = runTest {
        val tracker = RouteReadyEventTracker()
        val generation = tracker.beginTransfer()
        tracker.beginVerification(generation)

        val result = async { tracker.awaitVerificationEvent(generation, 1000L) }
        tracker.onStatus("RECEIVING,448/448")
        tracker.onStatus("ROUTE_READY,448/448")

        assertTrue(result.await() is RouteVerificationEvent.Ready)
    }

    @Test
    fun failedTransferDoesNotLeakSuccessIntoNextTransfer() = runTest {
        val tracker = RouteReadyEventTracker()
        val failedGeneration = tracker.beginTransfer()
        tracker.beginVerification(failedGeneration)
        val failedResult = async { tracker.awaitVerificationEvent(failedGeneration, 1000L) }
        tracker.onStatus("ERROR,3/22")
        assertEquals(
            RouteVerificationEvent.Error("ERROR,3/22"),
            failedResult.await()
        )

        val nextGeneration = tracker.beginTransfer()
        tracker.beginVerification(nextGeneration)
        val nextResult = async { tracker.awaitVerificationEvent(nextGeneration, 100L) }
        advanceUntilIdle()

        assertFalse(nextResult.await() is RouteVerificationEvent.Ready)
    }

    @Test
    fun previousGenerationCannotBeginVerificationAfterReplacement() {
        val tracker = RouteReadyEventTracker()
        val firstGeneration = tracker.beginTransfer()
        tracker.beginTransfer()

        try {
            tracker.beginVerification(firstGeneration)
            throw AssertionError("A replaced transfer generation must not begin verification")
        } catch (_: IllegalStateException) {
            // Expected: the previous transfer no longer owns verification.
        }
    }
}