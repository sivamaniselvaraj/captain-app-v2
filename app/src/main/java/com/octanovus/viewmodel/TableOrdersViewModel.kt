package com.octanovus.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octanovus.data.ApiClient
import com.octanovus.data.ApiResult
import com.octanovus.data.AppPrefs
import com.octanovus.data.OrderItem
import com.octanovus.data.OrderStep
import com.octanovus.data.TableInfo
import com.octanovus.data.toMyOrders
import com.octanovus.data.toOrderDetail
import com.octanovus.data.toSteps
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** One order (round) of a table, as the sheet shows it. */
data class SheetOrder(
    val orderNumber: String,
    val placedAt: Instant?,
    val items: List<OrderItem>,
    /** True when the dishes carry a kitchen step (the waiter's own order). */
    val hasSteps: Boolean,
)

data class TableOrdersState(
    val table: TableInfo? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val orders: List<SheetOrder> = emptyList(),
    val steps: List<OrderStep> = emptyList(),
)

/**
 * Loads the orders of one table for the "Table N orders" sheet.
 * The waiter's own orders come from GET /api/orders/mine, with each dish's kitchen step.
 * Any other order on the table (for example one taken by the waiter of the last shift) is read
 * from GET /api/order/{orderId}, which has no kitchen step.
 */
class TableOrdersViewModel(app: Application) : AndroidViewModel(app) {

    private val api = ApiClient(AppPrefs(app))

    private val _state = MutableStateFlow(TableOrdersState())
    val state: StateFlow<TableOrdersState> = _state.asStateFlow()

    private var job: Job? = null

    fun open(table: TableInfo) {
        job?.cancel()
        _state.value = TableOrdersState(table = table, loading = true)
        job = viewModelScope.launch {
            val steps = (api.protectedGet("/api/overdue-thresholds") as? ApiResult.Ok)?.value?.toSteps().orEmpty()

            val mine = when (val r = api.protectedGet("/api/orders/mine")) {
                is ApiResult.Ok -> r.value.toMyOrders().associateBy { it.orderId }
                is ApiResult.Err -> emptyMap()
            }

            val result = ArrayList<SheetOrder>()
            var failed: String? = null
            for (ref in table.orders) {
                val own = mine[ref.orderId]
                if (own != null) {
                    result += SheetOrder(ref.orderNumber, own.placedAt, own.items, hasSteps = true)
                    continue
                }
                when (val r = api.protectedGet("/api/order/${ref.orderId}")) {
                    is ApiResult.Ok -> {
                        val d = r.value.toOrderDetail()
                        result += SheetOrder(ref.orderNumber, d.placedAt, d.items, hasSteps = false)
                    }

                    is ApiResult.Err -> failed = r.message
                }
            }
            _state.value = TableOrdersState(
                table = table,
                loading = false,
                orders = result,
                steps = steps,
                error = if (failed != null && result.isEmpty()) failed else null,
            )
        }
    }

    fun close() {
        job?.cancel()
        _state.value = TableOrdersState()
    }
}
