package com.octanovus.data

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/** One table from GET /api/tables. Only the fields the phone uses. */
data class TableInfo(
    val tableId: String,
    val number: String,
    val floor: String,
    /** available, occupied or completed. */
    val status: String,
    val total: Double,
    /** When the table's first open order was placed. Null when the table is free. */
    val since: Instant?,
    /** Number of open rounds on the table. */
    val openRounds: Int,
) {
    val isAvailable: Boolean get() = status == STATUS_AVAILABLE
    val isOccupied: Boolean get() = status == STATUS_OCCUPIED
    val isCompleted: Boolean get() = status == STATUS_COMPLETED

    companion object {
        const val STATUS_AVAILABLE = "available"
        const val STATUS_OCCUPIED = "occupied"
        const val STATUS_COMPLETED = "completed"
    }
}

/** One overdue level from GET /api/overdue-thresholds. It applies from [fromMinutes] onward. */
data class OverdueLevel(
    val name: String,
    val fromMinutes: Int,
    /** Colour as sent by the desktop, for example "#f1c40f". */
    val color: String,
    val notify: Boolean,
)

/** The highest level reached after [minutes], or null when there are no levels. */
fun List<OverdueLevel>.levelAt(minutes: Int): OverdueLevel? =
    filter { it.fromMinutes <= minutes }.maxByOrNull { it.fromMinutes }

fun JSONObject.toTables(): List<TableInfo> {
    val array = optJSONArray("tables") ?: return emptyList()
    val list = ArrayList<TableInfo>(array.length())
    for (i in 0 until array.length()) {
        val t = array.optJSONObject(i) ?: continue
        val id = t.optString("tableId")
        if (id.isBlank()) continue
        list += TableInfo(
            tableId = id,
            number = t.optString("tableNumber").ifBlank { "?" },
            floor = if (t.isNull("floor")) "" else t.optString("floor").trim(),
            status = t.optString("status"),
            total = t.optDouble("total", 0.0).let { if (it.isNaN()) 0.0 else it },
            since = if (t.isNull("since")) null else parseInstant(t.optString("since")),
            openRounds = t.optJSONArray("orders")?.length() ?: 0,
        )
    }
    return list.sortedWith(
        compareBy<TableInfo>({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number })
    )
}

fun JSONObject.toLevels(): List<OverdueLevel> {
    val array = optJSONArray("levels") ?: return emptyList()
    val list = ArrayList<OverdueLevel>(array.length())
    for (i in 0 until array.length()) {
        val l = array.optJSONObject(i) ?: continue
        list += OverdueLevel(
            name = l.optString("name"),
            fromMinutes = l.optInt("fromMinutes", 0),
            color = l.optString("color"),
            notify = l.optBoolean("notify", false),
        )
    }
    return list.sortedBy { it.fromMinutes }
}

private fun parseInstant(text: String): Instant? =
    runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
