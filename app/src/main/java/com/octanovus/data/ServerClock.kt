package com.octanovus.data

import java.time.Duration
import java.time.Instant

/**
 * The desktop's clock. Waiting minutes are counted against it, so a phone with a wrong clock
 * still shows the right times. The desktop sends `serverTime` with the waiting-time levels.
 */
object ServerClock {
    @Volatile
    private var offsetMs = 0L

    fun sync(serverTime: String?) {
        val server = runCatching { Instant.parse(serverTime) }.getOrNull() ?: return
        offsetMs = Duration.between(Instant.now(), server).toMillis()
    }

    fun now(): Instant = Instant.now().plusMillis(offsetMs)
}
