package com.octanovus.data

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter

/**
 * Live "something changed" signals from the desktop (`event: changed` on `GET /api/events?live=1`).
 *
 * The stream carries no id and is never replayed. Screens listen for the scope they show
 * ("tables" or "orders") and reload. ETag polling stays as a safety net: it runs every
 * [SLOW_POLL_MS] while the stream is connected and the desktop has sent at least one "changed"
 * event. An older desktop never sends one, so the app keeps its normal polling rate there.
 */
object LiveUpdates {

    const val TABLES = "tables"
    const val ORDERS = "orders"

    /** Safety-net poll interval while the live stream is working. */
    const val SLOW_POLL_MS = 45_000L

    /** Bursts of events are merged into one reload. */
    private const val DEBOUNCE_MS = 250L

    private val _scopes = MutableSharedFlow<String>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val scopes: SharedFlow<String> = _scopes.asSharedFlow()

    /** True once this desktop has sent a "changed" event, which means it supports live updates. */
    @Volatile
    var desktopIsLive: Boolean = false
        private set

    /** The stream delivered a "changed" event. */
    fun onChanged(scopes: List<String>) {
        desktopIsLive = true
        scopes.forEach { _scopes.tryEmit(it) }
    }

    /** The stream just (re)connected: reload everything once, in case events were missed. */
    fun onConnected() {
        _scopes.tryEmit(TABLES)
        _scopes.tryEmit(ORDERS)
    }

    /** Slow polling is safe only while the stream is up and the desktop is known to send events. */
    fun isLive(): Boolean =
        desktopIsLive && NotificationHub.connection.value == NotificationHub.Connection.CONNECTED

    /**
     * Waits between safety-net polls: [fastMs] normally, [SLOW_POLL_MS] while live. Re-checks every
     * half second, so polling speeds up again soon after the stream drops.
     */
    suspend fun pause(fastMs: Long) {
        var waited = 0L
        while (true) {
            val target = if (isLive()) maxOf(SLOW_POLL_MS, fastMs) else fastMs
            if (waited >= target) return
            delay(500)
            waited += 500
        }
    }

    /** Runs [reload] when a "changed" event names [scope], merging a burst into one call. */
    @OptIn(FlowPreview::class)
    suspend fun reloadOn(scope: String, reload: suspend () -> Unit) {
        scopes.filter { it == scope }.debounce(DEBOUNCE_MS).collect { reload() }
    }
}
