package com.octanovus.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The PIN for the current app session, kept in memory only.
 * It is set after a successful PIN login and is sent with every desktop request.
 * It is never written to disk and is cleared when the app process ends.
 */
object Session {
    /** The waiter's PIN. Sent as `X-Captain-Pin`. */
    @Volatile
    var pin: String? = null

    /** The unlock token (hash) the desktop returns from the PIN login. Sent as `X-Unlock-Token`. */
    @Volatile
    var unlockToken: String? = null

    private val _expired = MutableStateFlow(false)

    /** True when the desktop said the PIN session ended (idle too long). The app returns to PIN login. */
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    /** The desktop asked for the PIN again: forget the login and tell the app. */
    fun expire() {
        clear()
        _expired.value = true
    }

    /** The app has taken the waiter to PIN login. */
    fun acknowledgeExpired() {
        _expired.value = false
    }

    fun clear() {
        pin = null
        unlockToken = null
    }
}
