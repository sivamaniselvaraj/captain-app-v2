package com.octanovus.data

import org.json.JSONObject
import java.time.Instant

/**
 * One order step from GET /api/overdue-thresholds: new, preparing, ready, served.
 * The phone uses [isFinal] to know when an item is done, not a fixed step name.
 */
data class OrderStep(
    val code: String,
    val name: String,
    val color: String,
    val sortOrder: Int,
    val isFinal: Boolean,
)

fun JSONObject.toSteps(): List<OrderStep> {
    val array = optJSONArray("steps") ?: return emptyList()
    val list = ArrayList<OrderStep>(array.length())
    for (i in 0 until array.length()) {
        val s = array.optJSONObject(i) ?: continue
        val code = s.optString("code")
        if (code.isBlank()) continue
        list += OrderStep(
            code = code,
            name = s.optString("name").ifBlank { code },
            color = s.optString("color"),
            sortOrder = s.optInt("sortOrder", i),
            isFinal = s.optBoolean("isFinal", false),
        )
    }
    return list.sortedBy { it.sortOrder }
}

/** One item of an order placed by this device. */
data class OrderItem(
    val id: String,
    val name: String,
    val quantity: Int,
    /** Step code: new, preparing, ready or served. */
    val step: String,
)

/** An order this device placed, from GET /api/orders/mine. */
data class MyOrder(
    val orderId: String,
    val orderNumber: String,
    val tableId: String,
    val tableNumber: String,
    val floor: String,
    /** When the order was placed. The overdue clock starts here. */
    val placedAt: Instant?,
    val step: String,
    val total: Double,
    val items: List<OrderItem>,
)

/**
 * PROPOSED reply of `GET /api/orders/mine` (to be confirmed by the desktop team):
 * { "orders": [ { "orderId", "orderNumber", "tableId", "tableNumber", "floor", "placedAt",
 *                 "step", "total",
 *                 "items": [ { "id", "name", "quantity", "step" } ] } ] }
 * The desktop lists orders until the table is settled, and keeps served items in the list.
 */
fun JSONObject.toMyOrders(): List<MyOrder> {
    val array = optJSONArray("orders") ?: return emptyList()
    val list = ArrayList<MyOrder>(array.length())
    for (i in 0 until array.length()) {
        val o = array.optJSONObject(i) ?: continue
        val orderId = o.optString("orderId")
        val tableId = o.optString("tableId")
        if (orderId.isBlank() || tableId.isBlank()) continue
        val orderStep = o.optString("step")
        val items = ArrayList<OrderItem>()
        val itemArray = o.optJSONArray("items")
        if (itemArray != null) {
            for (j in 0 until itemArray.length()) {
                val it = itemArray.optJSONObject(j) ?: continue
                items += OrderItem(
                    id = it.optString("id").ifBlank { "$orderId-$j" },
                    name = it.optString("name").ifBlank { "Item" },
                    quantity = it.optInt("quantity", 1),
                    step = it.optString("step").ifBlank { orderStep },
                )
            }
        }
        list += MyOrder(
            orderId = orderId,
            orderNumber = o.optString("orderNumber").ifBlank { "?" },
            tableId = tableId,
            tableNumber = o.optString("tableNumber").ifBlank { "?" },
            floor = if (o.isNull("floor")) "" else o.optString("floor").trim(),
            placedAt = if (o.isNull("placedAt")) null else parseInstant(o.optString("placedAt")),
            step = orderStep,
            total = o.optDouble("total", 0.0).let { v -> if (v.isNaN()) 0.0 else v },
            items = items,
        )
    }
    return list
}
