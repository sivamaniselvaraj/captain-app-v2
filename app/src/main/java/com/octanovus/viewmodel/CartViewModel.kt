package com.octanovus.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octanovus.data.ApiClient
import com.octanovus.data.ApiResult
import com.octanovus.data.AppPrefs
import com.octanovus.data.TableInfo
import com.octanovus.data.MenuItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One line of the cart. The item is kept as it was when added, so the line survives a menu refresh. */
data class CartLine(val item: MenuItem, val quantity: Int, val note: String = "") {
    val lineTotal: Double get() = item.price * quantity
}

/** Tax added on top of prices, for one tax name and rate, for example "GST 5%". */
data class TaxLine(val title: String, val amount: Double)

data class CartState(
    val tableId: String? = null,
    val tableNumber: String = "",
    /** Takeaway only: the table the order is delivered to. Sent in the order notes. */
    val deliverTo: String? = null,
    /** Keyed by menu item id, in the order items were first added. */
    val lines: Map<String, CartLine> = emptyMap(),
    /** The "Additional instruction" for the whole order. Sent as `notes`. */
    val notes: String = "",
    /** Optional guest details, sent as `customerName` and `customerPhone`. */
    val customerName: String = "",
    val customerPhone: String = "",
    /** Number of people at the table. Optional. */
    val occupants: Int? = null,
) {
    /**
     * The customer details screen shows whenever a new order starts on a table with no orders
     * placed, even if the waiter skipped it before without adding anything. It is not shown again
     * while this table's cart already has items (the waiter is mid-order), nor for an extra round
     * on an occupied table.
     */
    fun needsCustomerStep(table: TableInfo): Boolean =
        table.startsNewOrder && !(tableId == table.tableId && lines.isNotEmpty())

    val totalQuantity: Int get() = lines.values.sumOf { it.quantity }
    fun quantityOf(menuItemId: String): Int = lines[menuItemId]?.quantity ?: 0

    /** A new item can't be added once the order has the most lines the desktop accepts. */
    fun canAdd(menuItemId: String): Boolean {
        val current = quantityOf(menuItemId)
        return if (current == 0) lines.size < MAX_LINES else current < MAX_QUANTITY
    }

    /** Sum of the line prices as listed. Prices that include tax stay as they are. */
    val subtotal: Double get() = lines.values.sumOf { it.lineTotal }

    /** Tax on top of the price, only for items whose price excludes tax. One line per tax name and rate. */
    val taxLines: List<TaxLine>
        get() = lines.values
            .filter { !it.item.priceIncludesTax && it.item.taxTitle != null }
            .groupBy { it.item.taxTitle!! }
            .map { (title, group) ->
                TaxLine(title, group.sumOf { it.lineTotal * it.item.taxRatePercent / 100.0 })
            }

    /** An estimate. The desktop works out the real bill. */
    val total: Double get() = subtotal + taxLines.sumOf { it.amount }
}

/** What the desktop confirmed after "Place order". */
data class PlacedOrder(val orderNumber: String, val tableNumber: String, val deliverTo: String? = null)

data class PlaceState(
    val placing: Boolean = false,
    val error: String? = null,
    val placed: PlacedOrder? = null,
    /** The desktop says this device is no longer paired. The app goes back to pairing. */
    val unauthorised: Boolean = false,
)

private const val SEND_OCCUPANTS = false

const val MAX_LINES = 60
const val MAX_QUANTITY = 99
const val MAX_NOTES_LENGTH = 200
const val MAX_ITEM_NOTE_LENGTH = 100

/** The line the desktop reads back to show which table a takeaway is for. */
fun takeawayNote(table: String) = "Takeaway for Table $table"

/**
 * The order being built for one table. Shared by the menu, View order and placing the order.
 * Choosing a different table starts an empty cart. Choosing the same table again keeps it.
 */
class CartViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val api = ApiClient(prefs)

    private val _state = MutableStateFlow(CartState())
    val state: StateFlow<CartState> = _state.asStateFlow()

    private val _place = MutableStateFlow(PlaceState())
    val place: StateFlow<PlaceState> = _place.asStateFlow()

    /**
     * Sent as `clientRequestId`. Made when the waiter taps "Place order" and reused if the same
     * order is sent again after a failure, so a lost reply never creates a second order.
     * Dropped as soon as the order changes.
     */
    private var requestId: String? = null

    fun startFor(table: TableInfo) {
        _state.update {
            if (it.tableId == table.tableId && it.deliverTo == table.deliverTo) it
            else CartState(table.tableId, table.number, table.deliverTo)
        }
    }

    fun add(item: MenuItem) {
        _state.update { cart ->
            if (!cart.canAdd(item.id)) return@update cart
            val current = cart.lines[item.id]
            val line = CartLine(current?.item ?: item, (current?.quantity ?: 0) + 1, current?.note.orEmpty())
            cart.copy(lines = cart.lines + (item.id to line))
        }
        orderChanged()
    }

    fun remove(menuItemId: String) {
        _state.update { cart ->
            val current = cart.lines[menuItemId] ?: return@update cart
            if (current.quantity <= 1) cart.copy(lines = cart.lines - menuItemId)
            else cart.copy(lines = cart.lines + (menuItemId to current.copy(quantity = current.quantity - 1)))
        }
        orderChanged()
    }

    /** Removes the whole line, whatever its quantity. */
    fun removeLine(menuItemId: String) {
        _state.update { it.copy(lines = it.lines - menuItemId) }
        orderChanged()
    }

    /** Sets or clears the note on one line. One note covers the whole quantity. */
    fun setItemNote(menuItemId: String, note: String) {
        _state.update { cart ->
            val line = cart.lines[menuItemId] ?: return@update cart
            cart.copy(lines = cart.lines + (menuItemId to line.copy(note = note.trim().take(MAX_ITEM_NOTE_LENGTH))))
        }
        orderChanged()
    }

    /** Saves the guest details for this table's new order. */
    fun setCustomer(table: TableInfo, name: String, phone: String, occupants: Int?) {
        startFor(table)
        _state.update {
            it.copy(
                customerName = name.trim(),
                customerPhone = phone.trim(),
                occupants = occupants,
            )
        }
        orderChanged()
    }

    /** The waiter skipped the customer details. Any details entered before are cleared. */
    fun skipCustomer(table: TableInfo) {
        startFor(table)
        _state.update { it.copy(customerName = "", customerPhone = "", occupants = null) }
        orderChanged()
    }

    fun setNotes(value: String) {
        _state.update { it.copy(notes = value.take(MAX_NOTES_LENGTH)) }
        orderChanged()
    }

    fun clear() {
        _state.update { it.copy(lines = emptyMap(), notes = "", customerName = "", customerPhone = "", occupants = null) }
        orderChanged()
    }

    /** The waiter has seen the "Order sent" confirmation. */
    fun consumePlaced() = _place.update { it.copy(placed = null) }

    /** Sends the cart to the desktop as a dine-in order, a takeaway order, or as an extra round on an occupied table. */
    fun placeOrder() {
        val cart = _state.value
        val tableId = cart.tableId ?: return
        if (cart.lines.isEmpty() || _place.value.placing) return

        val id = requestId ?: UUID.randomUUID().toString().also { requestId = it }
        val items = JSONArray()
        cart.lines.values.forEach {
            val line = JSONObject().put("menuItemId", it.item.id)
                .put("quantity", it.quantity)
                .put("unitPrice", it.item.price)
                .put("totalPrice", it.quantity * it.item.price)
                // PROPOSED: the desktop accepts an optional `notes` on each item.
            if (it.note.isNotBlank()) line.put("notes", it.note)
               items.put(line)
        }
        val isTakeaway = tableId == TableInfo.TAKEAWAY_ID
        // Same API for both. A takeaway order has its own order type and no table.
        val body = JSONObject()
            .put("orderType", if (isTakeaway) "takeaway" else "dine_in")
            .put("items", items)
            .put("clientRequestId", id)
        if (!isTakeaway) body.put("tableId", tableId)
        // A takeaway carries its table in the order notes (not in table_id), then the waiter's instruction.
        val orderNotes = listOfNotNull(
            if (isTakeaway) cart.deliverTo?.let(::takeawayNote) else null,
            cart.notes.trim().takeIf { it.isNotEmpty() },
        ).joinToString("\n")
        if (orderNotes.isNotEmpty()) body.put("notes", orderNotes)
        cart.customerName.takeIf { it.isNotBlank() }?.let { body.put("customerName", it) }
        cart.customerPhone.takeIf { it.isNotBlank() }?.let { body.put("customerPhone", it) }
        // The order API has no field for the number of guests yet, so it stays on the phone.
        // Set SEND_OCCUPANTS to true, and check the field name, once the desktop accepts it.
        if (SEND_OCCUPANTS) cart.occupants?.let { body.put("occupants", it) }

        viewModelScope.launch {
            _place.update { it.copy(placing = true, error = null) }
            when (val result = api.protectedPost("/api/orders", body)) {
                is ApiResult.Ok -> {
                    // 201 for a new order, 200 when the desktop already had this clientRequestId.
                    // Either way the order exists, so the cart is emptied.
                    val orderNumber = result.value.optString("orderNumber").ifBlank { "?" }
                    // The next order for this table starts clean, and asks for customer details again
                    // only if the table is free again.
                    _state.update {
                        it.copy(
                            lines = emptyMap(),
                            notes = "",
                            customerName = "",
                            customerPhone = "",
                            occupants = null,
                        )
                    }
                    requestId = null
                    _place.value = PlaceState(placed = PlacedOrder(orderNumber, cart.tableNumber, cart.deliverTo))
                }
                is ApiResult.Err -> {
                    val notPaired = result.code == 401 ||
                        (result.code == 403 && result.message.contains("Pair it again", ignoreCase = true))
                    if (notPaired) {
                        prefs.clearPairing()
                        _place.update { it.copy(placing = false, unauthorised = true) }
                    } else {
                        val message = when (result.code) {
                            429 -> "Too many requests. Wait a moment, then try again."
                            503 -> "Desktop app is not ready. Your order is kept; try again."
                            null -> "${result.message} Your order is kept; tap Place order to try again."
                            else -> result.message
                        }
                        _place.update { it.copy(placing = false, error = message) }
                    }
                }
            }
        }
    }

    private fun orderChanged() {
        requestId = null
        if (_place.value.error != null) _place.update { it.copy(error = null) }
    }
}
