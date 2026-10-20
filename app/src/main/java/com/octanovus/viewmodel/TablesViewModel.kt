package com.octanovus.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.octanovus.data.ApiClient
import com.octanovus.data.ApiResult
import com.octanovus.data.AppPrefs
import com.octanovus.data.OverdueLevel
import com.octanovus.data.TableInfo
import com.octanovus.data.toLevels
import com.octanovus.data.toTables

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import kotlin.collections.filter
import kotlin.collections.map

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
    suspend fun pollLoop() {
        while (true) {
            delay(refresh())
        }
    }

    /** One refresh. Returns how many milliseconds to wait before the next one. */
    suspend fun refresh(): Long {
        if (_state.value.levels.isEmpty()) loadLevels()

        return when (val result = api.getIfChanged("/api/tables", etag)) {
            is ApiResult.Ok -> {
                val reply = result.value
                etag = reply.etag
                _state.update {
                    it.copy(
                        // 304: keep the list we have.
                        tables = reply.json?.toTables() ?: it.tables,
                        now = Instant.now(),
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
                _state.update { it.copy(error = message, now = Instant.now()) }
                if (result.code == 429) RATE_LIMIT_WAIT_MS else POLL_MS
            }
        }
    }

    /** Levels are loaded once, and again on a later poll if the first try failed. */
    private suspend fun loadLevels() {
        val result = api.protectedGet("/api/overdue-thresholds")
        if (result is ApiResult.Ok) {
            val levels = result.value.toLevels()
            _state.update { it.copy(levels = levels) }
        }
    }

    companion object {
        private const val POLL_MS = 3_000L
        private const val RATE_LIMIT_WAIT_MS = 10_000L
    }
}
