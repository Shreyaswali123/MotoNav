package com.example

import com.example.ble.ScanAttemptCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBleScanTest {

    @Test
    fun delayedResultAfterStopScanIsIgnored() {
        val scans = ScanAttemptCoordinator()
        val generation = scans.begin()

        scans.invalidate(generation)

        assertFalse(scans.isCurrent(generation))
        assertFalse(scans.claim(generation))
    }

    @Test
    fun delayedResultAfterDisconnectIsIgnored() {
        val scans = ScanAttemptCoordinator()
        val generation = scans.begin()

        scans.invalidate()

        assertFalse(scans.isCurrent(generation))
        assertNull(scans.currentGeneration())
    }

    @Test
    fun oldScanCallbackCannotInterfereWithNewScan() {
        val scans = ScanAttemptCoordinator()
        val oldGeneration = scans.begin()
        val newGeneration = scans.begin()

        assertNotEquals(oldGeneration, newGeneration)
        assertFalse(scans.isCurrent(oldGeneration))
        assertTrue(scans.isCurrent(newGeneration))
        assertFalse(scans.claim(oldGeneration))
        assertTrue(scans.isCurrent(newGeneration))
    }

    @Test
    fun duplicateMatchingResultAfterTargetClaimCannotCreateSecondClaim() {
        val scans = ScanAttemptCoordinator()
        val generation = scans.begin()

        assertTrue(scans.claim(generation))
        assertFalse(scans.claim(generation))
        assertFalse(scans.isCurrent(generation))
    }

    @Test
    fun oldScanTimeoutCannotInvalidateNewScan() {
        val scans = ScanAttemptCoordinator()
        val oldGeneration = scans.begin()
        val newGeneration = scans.begin()

        scans.invalidate(oldGeneration)

        assertTrue(scans.isCurrent(newGeneration))
        assertEquals(newGeneration, scans.currentGeneration())
    }

    @Test
    fun staleScanFailureCannotInvalidateNewScan() {
        val scans = ScanAttemptCoordinator()
        val oldGeneration = scans.begin()
        val newGeneration = scans.begin()

        scans.invalidate(oldGeneration)

        assertTrue(scans.isCurrent(newGeneration))
        assertTrue(scans.claim(newGeneration))
    }

    @Test
    fun currentScanCanClaimExactlyOneTarget() {
        val scans = ScanAttemptCoordinator()
        val generation = scans.begin()

        assertTrue(scans.isCurrent(generation))
        assertTrue(scans.claim(generation))
        assertFalse(scans.isCurrent(generation))
    }

    @Test
    fun cleanupInvalidatesTheActiveGeneration() {
        val scans = ScanAttemptCoordinator()
        val generation = scans.begin()

        scans.invalidate()

        assertNull(scans.currentGeneration())
        assertFalse(scans.claim(generation))
    }

    @Test
    fun everyNewScanReceivesDistinctGenerationOwnership() {
        val scans = ScanAttemptCoordinator()
        val first = scans.begin()
        scans.invalidate(first)
        val second = scans.begin()

        assertNotEquals(first, second)
        assertFalse(scans.isCurrent(first))
        assertTrue(scans.isCurrent(second))
    }

    @Test
    fun latePreviousGenerationCallbackCannotClaimAfterReconnectScan() {
        val scans = ScanAttemptCoordinator()
        val previous = scans.begin()
        scans.invalidate(previous)
        val current = scans.begin()

        assertFalse(scans.claim(previous))
        assertTrue(scans.isCurrent(current))
        assertTrue(scans.claim(current))
    }
}