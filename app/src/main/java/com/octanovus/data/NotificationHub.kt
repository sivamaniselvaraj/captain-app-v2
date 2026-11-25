package com.octanovus.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * The phone's notifications, shared by the background service and the screens.
 * It keeps the recent list, the last id received (for catching up after a reconnect), and how many
 * the waiter has not opened yet. The list is saved on the phone, so it survives a restart.
 */
object NotificationHub {

    enum class Connection { CONNECTED, CONNECTING, OFFLINE }

    private const val FILE = "captain_notifications"
    private const val MAX_ITEMS = 100

    private lateinit var prefs: SharedPreferences

    private val _items = MutableStateFlow<List<AppNotification>>(emptyList())
    val items: StateFlow<List<AppNotification>> = _items.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    private val _connection = MutableStateFlow(Connection.OFFLINE)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    /** The highest notification id received. Zero when nothing has been received yet. */
    @Volatile
    var lastId: Long = 0L
        private set

    /** Notifications up to this id have been opened by the waiter. */
    @Volatile
    var seenId: Long = 0L
        private set

    /** Safe to call many times. */
    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        lastId = prefs.getLong("last_id", 0L)
        seenId = prefs.getLong("seen_id", 0L)
        val saved = runCatching { JSONArray(prefs.getString("items", "[]")).toNotificationList() }
            .getOrDefault(emptyList())
        _items.value = saved.sortedByDescending { it.id }
        refreshUnread()
    }

    /**
     * Adds notifications once each (matched on id, so the live stream and a catch-up never double up).
     * Returns only the new ones, oldest first. `cancelled` notifications are dropped.
     */
    @Synchronized
    fun receive(incoming: List<AppNotification>): List<AppNotification> {
        if (!::prefs.isInitialized) return emptyList()
        // Ids restart at 1 when the desktop restarts. An id lower than the stored one means that
        // happened: forget the old ids, or every new alert would look like one already seen.
        if (lastId > 0 && incoming.any { it.id < lastId }) {
            _items.value = emptyList()
            lastId = 0L
            seenId = 0L
        }
        incoming.maxOfOrNull { it.id }?.let { if (it > lastId) lastId = it }
        val known = _items.value.map { it.id }.toHashSet()
        val fresh = incoming.filter { it.type != "cancelled" }.filter { known.add(it.id) }
        if (fresh.isNotEmpty()) {
            _items.value = (fresh + _items.value).sortedByDescending { it.id }.take(MAX_ITEMS)
        }
        persist()
        refreshUnread()
        return fresh.sortedBy { it.id }
    }

    /** The waiter opened the Notifications screen. */
    @Synchronized
    fun markAllSeen() {
        if (!::prefs.isInitialized) return
        seenId = _items.value.maxOfOrNull { it.id } ?: seenId
        persist()
        refreshUnread()
    }

    /** Forgets everything. Used when the phone is unpaired. */
    @Synchronized
    fun clear() {
        if (!::prefs.isInitialized) return
        _items.value = emptyList()
        lastId = 0L
        seenId = 0L
        _connection.value = Connection.OFFLINE
        persist()
        refreshUnread()
    }

    fun setConnection(value: Connection) {
        _connection.value = value
    }

    private fun refreshUnread() {
        _unread.value = _items.value.count { it.id > seenId }
    }

    private fun persist() {
        val array = JSONArray()
        _items.value.forEach { array.put(it.toJson()) }
        prefs.edit()
            .putLong("last_id", lastId)
            .putLong("seen_id", seenId)
            .putString("items", array.toString())
            .apply()
    }
}
