package com.octanovus.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octanovus.data.ApiClient
import com.octanovus.data.ApiResult
import com.octanovus.data.AppPrefs
import com.octanovus.data.LiveUpdates
import com.octanovus.data.OverdueLevel
import com.octanovus.data.ServerClock
import com.octanovus.data.TableInfo
import com.octanovus.data.toLevels
import com.octanovus.data.toMyOrders
import com.octanovus.data.toTables
import kotlinx.coroutines.coroutineScope

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

data class TablesState(
    val tables: List<TableInfo> = emptyList(),
    val levels: List<OverdueLevel> = emptyList(),
    val selectedFloor: String? = null,
    /** Time of the last poll. Minutes on the tiles are counted up to this moment. */
    val now: Instant = Instant.now(),
    val loaded: Boolean = false,
    val error: String? = null,
    /** The desktop rejected the device token. The app goes back to pairing. */
    val unauthorised: Boolean = false,
    /** The desktop reports mode "offline" (no internet there). Ordering still works. */
    val offlineMode: Boolean = false,
) {
    val floors: List<String> get() = tables.map { it.floor.ifBlank { DEFAULT_FLOOR } }.distinct()
    /** The chosen floor, or [ALL_FLOORS]. Shows all tables until a floor is chosen. */
    val activeFloor: String get() = selectedFloor?.takeIf { it in floors } ?: ALL_FLOORS
    val visibleTables: List<TableInfo>
        get() = if (activeFloor == ALL_FLOORS) tables
        else tables.filter { it.floor.ifBlank { DEFAULT_FLOOR } == activeFloor }

    companion object {
        const val DEFAULT_FLOOR = "Main"

        /** Stands for "all floors". Not a real floor name. */
        const val ALL_FLOORS = "\u0000all"
    }
}

/**
 * Keeps the table grid in sync with the desktop.
 * The screen calls [pollLoop] only while it is visible, so polling stops in the background.
 */
class TablesViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val api = ApiClient(prefs)

    private var etag: String? = null

    private val _state = MutableStateFlow(TablesState())
    val state: StateFlow<TablesState> = _state.asStateFlow()

    fun selectFloor(floor: String) = _state.update { it.copy(selectedFloor = floor) }

    /** Polls every 3 seconds until the calling coroutine is cancelled. */
    suspend fun pollLoop() = coroutineScope {
        // A live "tables" event reloads at once. The poll below is the safety net.
        launch { LiveUpdates.reloadOn(LiveUpdates.TABLES) { refresh() } }
        // A new round changes a table's total, so "orders" events reload the grid too.
        launch { LiveUpdates.reloadOn(LiveUpdates.ORDERS) { refresh() } }
        while (true) {
            LiveUpdates.pause(refresh())
        }
    }

    private val refreshLock = Mutex()
    private var healthCheckedAt = 0L
    private var levelsLoadedAt = 0L
    private fun now() = System.currentTimeMillis()

    /** One refresh. Returns how many milliseconds to wait before the next one (when not live). */
    suspend fun refresh(): Long = refreshLock.withLock { doRefresh() }

    /** GET /api/health every 10 s. Its "mode" is "offline" when the desktop has no internet. */
    private suspend fun checkMode() {
        val now = System.currentTimeMillis()
        if (now - healthCheckedAt < HEALTH_EVERY_MS) return
        healthCheckedAt = now
        val result = api.health()
        if (result is ApiResult.Ok) {
            val offline = result.value.optString("mode") == "offline"
            _state.update { it.copy(offlineMode = offline) }
        }
    }

    private suspend fun doRefresh(): Long {
        if (_state.value.levels.isEmpty() || now() - levelsLoadedAt > LEVELS_EVERY_MS) loadLevels()
        checkMode()

        return when (val result = api.getIfChanged("/api/tables", etag)) {
            is ApiResult.Ok -> {
                val reply = result.value
                etag = reply.etag
                _state.update {
                    it.copy(
                        // 304: keep the list we have.
                        tables = reply.json?.toTables() ?: it.tables,
                        now = ServerClock.now(),
                        loaded = true,
                        error = null,
                    )
                }
                POLL_MS
            }
            is ApiResult.Err -> {
                if (result.code == 401) {
                    // Token revoked or user deactivated: forget the pairing.
                    prefs.clearPairing()
                    _state.update { it.copy(unauthorised = true) }
                    return POLL_MS
                }
                val message = when (result.code) {
                    429 -> "Too many requests. Slowing down."
                    503 -> "Desktop app is not ready."
                    500 -> "Something went wrong on the desktop."
                    else -> result.message
                }
                // Keep the last tables on screen and show the problem above them.
                _state.update { it.copy(error = message, now = ServerClock.now()) }
                if (result.code == 429) (result.retryAfterSeconds?.times(1000L) ?: RATE_LIMIT_WAIT_MS) else POLL_MS
            }
        }
    }

    /** Levels are loaded once, and again on a later poll if the first try failed. */
    private suspend fun loadLevels() {
        val result = api.protectedGet("/api/overdue-thresholds")
        if (result is ApiResult.Ok) {
            levelsLoadedAt = now()
            ServerClock.sync(result.value.optString("serverTime"))
            val levels = result.value.toLevels()
            _state.update { it.copy(levels = levels) }
        }
    }

    companion object {
        private const val POLL_MS = 3_000L
        private const val RATE_LIMIT_WAIT_MS = 10_000L
        private const val HEALTH_EVERY_MS = 10_000L
        private const val LEVELS_EVERY_MS = 60_000L
    }
}
