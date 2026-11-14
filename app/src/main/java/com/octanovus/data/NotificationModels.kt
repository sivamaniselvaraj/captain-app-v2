package com.octanovus.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * A notification from the desktop, for an order this phone placed.
 * [type] is `delay` (order waited past a threshold) or `status` (order status changed).
 * `cancelled` notifications are dropped this iteration.
 */
data class AppNotification(
    val id: Long,
    val type: String,
    val orderId: String,
    val orderNumber: String,
    val tableNumber: String?,
    val title: String,
    val message: String,
    /** Step code for a `status` notification, for example `ready`. */
    val status: String?,
    /** Level name for a `delay` notification, for example "Getting late". */
    val level: String?,
    val at: Instant?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type)
        .put("orderId", orderId)
        .put("orderNumber", orderNumber)
        .put("tableNumber", tableNumber ?: JSONObject.NULL)
        .put("title", title)
        .put("message", message)
        .put("status", status ?: JSONObject.NULL)
        .put("level", level ?: JSONObject.NULL)
        .put("at", at?.toString() ?: JSONObject.NULL)
}

/** One notification object, as sent by `/api/events` and `/api/notifications`. Null if it is unusable. */
fun JSONObject.toNotification(): AppNotification? {
    val id = optLong("id", -1L)
    val type = optString("type")
    if (id < 0 || type.isBlank()) return null
    return AppNotification(
        id = id,
        type = type,
        orderId = optString("orderId"),
        orderNumber = optString("orderNumber"),
        tableNumber = nullableText("tableNumber"),
        title = optString("title"),
        message = optString("message"),
        status = nullableText("status"),
        level = nullableText("level"),
        at = nullableText("at")?.let { parseInstant(it) },
    )
}

/** The reply of `GET /api/notifications`: `{ "notifications": [ ... ] }`. */
fun JSONObject.toNotifications(): List<AppNotification> {
    val array = optJSONArray("notifications") ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toNotification() }
}

fun JSONArray.toNotificationList(): List<AppNotification> =
    (0 until length()).mapNotNull { optJSONObject(it)?.toNotification() }

private fun JSONObject.nullableText(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }
