package com.octanovus.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octanovus.data.ApiClient
import com.octanovus.data.ApiResult
import com.octanovus.data.AppPrefs
import com.octanovus.data.MyOrder
import com.octanovus.data.OrderStep
import com.octanovus.data.OverdueLevel
import com.octanovus.data.toLevels
import com.octanovus.data.toMyOrders
import com.octanovus.data.toSteps
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** All the orders this device placed on one table, shown as one card. */
data class TableGroup(
    val tableId: String,
    val tableNumber: String,
    val floor: String,
    val orders: List<MyOrder>,
    /** Minutes since the oldest order that still has items to serve. Null when everything is served. */
    val pendingMinutes: Int?,
) {
    val itemCount: Int get() = orders.sumOf { order -> order.items.sumOf { it.quantity } }
    val total: Double get() = orders.sumOf { it.total }
}

data class OrdersState(
    val orders: List<MyOrder> = emptyList(),
    val levels: List<OverdueLevel> = emptyList(),
    val steps: List<OrderStep> = emptyList(),
    val now: Instant = Instant.now(),
    val floorFilter: String? = null,
    val tableFilter: String? = null,
    /** Table ids whose card is folded shut. */
    val collapsed: Set<String> = emptySet(),
    /** Item ids whose "Ready to serve" request is on its way. */
    val serving: Set<String> = emptySet(),
    val loaded: Boolean = false,
    val error: String? = null,
    /** Shown after a "Ready to serve" request fails. */
    val actionError: String? = null,
    /** The desktop rejected the device token. The app goes back to pairing. */
    val unauthorised: Boolean = false,
) {
    /** Step codes that end an item's life. From the desktop, with "served" as the fallback. */
    val finalCodes: Set<String> get() = steps.filter { it.isFinal }.map { it.code }.toSet().ifEmpty { setOf("served") }

    fun isPending(order: MyOrder): Boolean =
        if (order.items.isEmpty()) order.step !in finalCodes else order.items.any { it.step !in finalCodes }

    private fun floorOf(order: MyOrder) = order.floor.ifBlank { TablesState.DEFAULT_FLOOR }

    val floors: List<String> get() = orders.map { floorOf(it) }.distinct()
    val activeFloor: String? get() = floorFilter?.takeIf { it in floors }

    /** Table numbers for the Table filter, limited to the chosen floor. */
    val tableNumbers: List<String>
        get() = orders.filter { activeFloor == null || floorOf(it) == activeFloor }
            .map { it.tableNumber }
            .distinct()
            .sortedWith(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
    val activeTable: String? get() = tableFilter?.takeIf { it in tableNumbers }

    /** Tables to show: the most overdue first, tables with everything served last. */
    val groups: List<TableGroup>
        get() = orders
            .filter { activeFloor == null || floorOf(it) == activeFloor }
            .filter { activeTable == null || it.tableNumber == activeTable }
            .groupBy { it.tableId }
            .map { (tableId, tableOrders) ->
                val oldestPending = tableOrders.filter(::isPending).mapNotNull { it.placedAt }.minOrNull()
                TableGroup(
                    tableId = tableId,
                    tableNumber = tableOrders.first().tableNumber,
                    floor = tableOrders.first().floor,
                    orders = tableOrders.sortedBy { it.placedAt },
                    pendingMinutes = oldestPending?.let { minutesSince(it, now) },
                )
            }
            .sortedWith(
                compareBy<TableGroup>({ it.pendingMinutes == null }, { -(it.pendingMinutes ?: 0) })
                    .thenBy { it.tableNumber.toIntOrNull() ?: Int.MAX_VALUE }
            )
}

private fun minutesSince(since: Instant, now: Instant): Int =
    Duration.between(since, now).toMinutes().coerceAtLeast(0).toInt()

/**
 * Keeps My orders in sync with the desktop while the screen is visible, and sends
 * "Ready to serve" for items. Needs two desktop endpoints that are still proposed:
 * GET /api/orders/mine and the mark-served call (see ApiClient).
 */
class OrdersViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val api = ApiClient(prefs)

    private var etag: String? = null

    private val _state = MutableStateFlow(OrdersState())
    val state: StateFlow<OrdersState> = _state.asStateFlow()

    fun selectFloor(floor: String?) = _state.update { it.copy(floorFilter = floor) }

    fun selectTable(number: String?) = _state.update { it.copy(tableFilter = number) }

    fun toggleCollapsed(tableId: String) = _state.update {
        it.copy(collapsed = if (tableId in it.collapsed) it.collapsed - tableId else it.collapsed + tableId)
    }

    fun dismissActionError() = _state.update { it.copy(actionError = null) }

    /** Polls every 5 seconds until the calling coroutine is cancelled (screen hidden). */
    suspend fun pollLoop() {
        while (true) {
            delay(refresh())
        }
    }

    /** One refresh. Returns how many milliseconds to wait before the next one. */
    suspend fun refresh(): Long {
        if (_state.value.steps.isEmpty() || _state.value.levels.isEmpty()) loadThresholds()

        return when (val result = api.getIfChanged("/api/orders/mine", etag)) {
            is ApiResult.Ok -> {
                val reply = result.value
                etag = reply.etag
                _state.update {
                    it.copy(
                        // 304: keep the list we have.
                        orders = reply.json?.toMyOrders() ?: it.orders,
                        now = Instant.now(),
                        loaded = true,
                        error = null,
                    )
                }
                POLL_MS
            }

            is ApiResult.Err -> {
                if (result.code == 401) {
                    prefs.clearPairing()
                    _state.update { it.copy(unauthorised = true) }
                    return POLL_MS
                }
                val message = when (result.code) {
                    404 -> "This desktop can't list your orders yet."
                    429 -> "Too many requests. Slowing down."
                    503 -> "Desktop app is not ready."
                    500 -> "Something went wrong on the desktop."
                    else -> result.message
                }
                _state.update { it.copy(error = message, now = Instant.now()) }
                if (result.code == 429) RATE_LIMIT_WAIT_MS else POLL_MS
            }
        }
    }

    /** The waiter switched "Ready to serve" on for an item. */
    fun markServed(orderId: String, itemId: String) {
        if (itemId in _state.value.serving) return
        _state.update { it.copy(serving = it.serving + itemId, actionError = null) }
        viewModelScope.launch {
            when (val result = api.markItemServed(orderId, itemId)) {
                is ApiResult.Ok -> _state.update { s ->
                    // The item shows as served and the switch goes away.
                    val finalCode = s.steps.firstOrNull { it.isFinal }?.code ?: "served"
                    s.copy(
                        serving = s.serving - itemId,
                        orders = s.orders.map { order ->
                            if (order.orderId != orderId) order
                            else order.copy(items = order.items.map { if (it.id == itemId) it.copy(step = finalCode) else it })
                        },
                    )
                }

                is ApiResult.Err -> {
                    if (result.code == 401) {
                        prefs.clearPairing()
                        _state.update { it.copy(serving = it.serving - itemId, unauthorised = true) }
                    } else {
                        // The switch goes back off and the waiter sees why.
                        _state.update { it.copy(serving = it.serving - itemId, actionError = result.message) }
                    }
                }
            }
        }
    }

    private suspend fun loadThresholds() {
        val result = api.protectedGet("/api/overdue-thresholds")
        if (result is ApiResult.Ok) {
            _state.update { it.copy(levels = result.value.toLevels(), steps = result.value.toSteps()) }
        }
    }

    companion object {
        private const val POLL_MS = 5_000L
        private const val RATE_LIMIT_WAIT_MS = 10_000L
    }
}
