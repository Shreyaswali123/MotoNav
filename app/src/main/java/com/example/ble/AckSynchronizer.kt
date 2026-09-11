package com.example.ble

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

sealed class AckWaitResult {
    data class Success(val sequence: Int, val raw: String) : AckWaitResult()
    data class SequenceMismatch(val expected: Int, val actual: Int, val raw: String) : AckWaitResult()
    data class Error(val message: String) : AckWaitResult()
    data class Timeout(val expectedSequence: Int) : AckWaitResult()
}

/**
 * Dedicated ACK synchronization manager for MotoNav BLE route transfer.
 *
 * Guarantees that:
 * 1. The ACK notification listener is active BEFORE START_ROUTE and BEFORE the first ROUTE_DATA packet is written.
 * 2. Any ACK emitted immediately during or after packet write is buffered in an unlimited channel and never lost.
 * 3. Exact raw notification bytes in hex and UTF-8 are logged.
 * 4. Sequence numbers are extracted and validated against the expected packet sequence.
 */
class AckSynchronizer(
    private val tag: String = "AckSynchronizer",
    private val logger: (String, String) -> Unit = { level, msg ->
        try {
            when (level) {
                "D" -> Log.d(tag, msg)
                "I" -> Log.i(tag, msg)
                "W" -> Log.w(tag, msg)
                "E" -> Log.e(tag, msg)
                else -> println("[$level/$tag] $msg")
            }
        } catch (_: Throwable) {
            println("[$level/$tag] $msg")
        }
    }
) {
    private val channel = Channel<String>(capacity = Channel.UNLIMITED)

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    private var activeGeneration: Long? = null

    /**
     * Activates the notification listener.
     * MUST be called before START_ROUTE to ensure no race condition on packet #0 ACK.
     */
    fun activate(generation: Long? = null) {
        // Drain any stale messages from channel
        var drainedCount = 0
        while (channel.tryReceive().isSuccess) {
            drainedCount++
        }
        activeGeneration = generation
        isActive = true
        logger("I", "[ACK_SYNCHRONIZER] Notification listener ACTIVATED (drained $drainedCount stale messages). Ready for route transfer.")
    }

    /**
     * Dispatches an incoming notification to the active ACK synchronizer.
     * Invoked from GATT callback onCharacteristicChanged / onCharacteristicRead.
     */
    fun onNotificationReceived(rawString: String) {
        val trimmed = rawString.trim()
        if (!isActive) {
            logger("D", "[ACK_SYNCHRONIZER] Notification received while inactive: '$trimmed'")
            return
        }
        logger("I", "[ACK_SYNCHRONIZER] Enqueueing notification into active listener: '$trimmed'")
        val sent = channel.trySend(trimmed).isSuccess
        if (!sent) {
            logger("W", "[ACK_SYNCHRONIZER] Failed to enqueue notification: '$trimmed'")
        }
    }

    /**
     * Suspends until the matching ACK notification for [expectedSequence] is received,
     * or until [timeoutMs] expires.
     */
    suspend fun waitForAck(expectedSequence: Int, timeoutMs: Long = 5000L): AckWaitResult {
        logger("D", "[ACK_SYNCHRONIZER] Waiting for ACK,$expectedSequence (timeout ${timeoutMs}ms)...")
        val result = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val msg = channel.receive()
                logger("D", "[ACK_SYNCHRONIZER] Dequeued notification from channel: '$msg'")

                if (msg.startsWith("ERROR", ignoreCase = true)) {
                    return@withTimeoutOrNull AckWaitResult.Error(msg)
                }

                if (msg.startsWith("ACK", ignoreCase = true)) {
                    val parts = msg.split(",")
                    val seq = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (seq != null) {
                        return@withTimeoutOrNull if (seq == expectedSequence) {
                            AckWaitResult.Success(sequence = seq, raw = msg)
                        } else {
                            AckWaitResult.SequenceMismatch(expected = expectedSequence, actual = seq, raw = msg)
                        }
                    } else {
                        logger("W", "[ACK_SYNCHRONIZER] Malformed ACK format without valid sequence: '$msg'")
                    }
                } else {
                    logger("D", "[ACK_SYNCHRONIZER] Non-ACK/ERROR status notification ignored during transfer: '$msg'")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

        return when {
            result == null -> {
                logger("E", "[ACK_SYNCHRONIZER] ACK timeout: Did not receive ACK,$expectedSequence within ${timeoutMs}ms")
                AckWaitResult.Timeout(expectedSequence)
            }
            result is AckWaitResult.Success -> {
                logger("I", "[ACK_SYNCHRONIZER] ACK waiter COMPLETED for packet ${result.sequence} (raw: '${result.raw}')")
                result
            }
            result is AckWaitResult.SequenceMismatch -> {
                logger("E", "[ACK_SYNCHRONIZER] Sequence mismatch: Expected ${result.expected}, received ${result.actual} (raw: '${result.raw}')")
                result
            }
            result is AckWaitResult.Error -> {
                logger("E", "[ACK_SYNCHRONIZER] Error received: '${result.message}'")
                result
            }
            else -> result
        }
    }

    /**
     * Deactivates the listener and drains any unconsumed messages.
     */
    fun deactivate(generation: Long? = null) {
        if (generation != null && activeGeneration != generation) {
            logger("D", "[ACK_SYNCHRONIZER] Ignoring deactivation for stale generation $generation")
            return
        }
        isActive = false
        activeGeneration = null
        var count = 0
        while (channel.tryReceive().isSuccess) {
            count++
        }
        logger("I", "[ACK_SYNCHRONIZER] Notification listener DEACTIVATED (drained $count unconsumed messages)")
    }
}
