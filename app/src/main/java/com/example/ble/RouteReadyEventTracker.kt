package com.example.ble

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

sealed interface RouteVerificationEvent {
    data class Ready(val message: String) : RouteVerificationEvent
    data class Error(val message: String) : RouteVerificationEvent
}

/** Tracks route-ready notifications independently from the diagnostic status snapshot. */
class RouteReadyEventTracker {
    private data class StatusEvent(
        val generation: Long,
        val message: String
    )

    private val nextGeneration = AtomicLong(0L)
    private val events = Channel<StatusEvent>(capacity = Channel.UNLIMITED)
    @Volatile
    private var activeGeneration = 0L

    fun beginTransfer(): Long {
        val generation = nextGeneration.incrementAndGet()
        activeGeneration = generation
        drainEvents()
        return generation
    }

    fun onStatus(message: String) {
        events.trySend(StatusEvent(activeGeneration, message))
    }

    fun beginVerification(generation: Long) {
        check(generation == activeGeneration) { "Route transfer generation is no longer active" }
        drainEvents()
    }

    suspend fun awaitVerificationEvent(
        generation: Long,
        timeoutMs: Long
    ): RouteVerificationEvent? {
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val event = events.receive()
                if (event.generation != generation) continue

                when {
                    event.message.startsWith("ROUTE_READY", ignoreCase = true) ->
                        return@withTimeoutOrNull RouteVerificationEvent.Ready(event.message)
                    event.message.startsWith("ERROR", ignoreCase = true) ->
                        return@withTimeoutOrNull RouteVerificationEvent.Error(event.message)
                }
            }
            null
        }
    }

    private fun drainEvents() {
        while (events.tryReceive().isSuccess) {
            // Discard notifications from the previous transfer boundary.
        }
    }
}