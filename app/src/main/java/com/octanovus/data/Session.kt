package com.octanovus.data

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

    fun clear() {
        pin = null
        unlockToken = null
    }
}
