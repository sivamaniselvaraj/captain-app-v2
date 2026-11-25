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
    /** Seats at the table. Null when the desktop has no capacity set. */
    val capacity: Int? = null,
    /** For the takeaway stand-in only: the number of the table the order is delivered to. */
    val deliverTo: String? = null,
) {
    val isAvailable: Boolean get() = status == STATUS_AVAILABLE
    val isOccupied: Boolean get() = status == STATUS_OCCUPIED
    val isCompleted: Boolean get() = status == STATUS_COMPLETED

    /** Reserved or being cleaned: shown, but not offered for new guests. */
    val isBlocked: Boolean get() = status == "reserved" || status == "cleaning"

    /** A tap starts a brand new order (not an extra round) unless the table is occupied or completed. */
    val startsNewOrder: Boolean get() = !isOccupied && !isCompleted

    /** The stand-in "table" for a takeaway order. It is not a real table on the desktop. */
    val isTakeaway: Boolean get() = tableId == TAKEAWAY_ID

    companion object {
        const val TAKEAWAY_ID = "takeaway"
        const val TAKEAWAY_LABEL = "Takeaway"

        /** Used to start a takeaway order: the same screens as a dine-in order, with no table. */
        fun takeaway(deliverTo: String) = TableInfo(
            tableId = TAKEAWAY_ID,
            number = TAKEAWAY_LABEL,
            floor = "",
            status = STATUS_AVAILABLE,
            total = 0.0,
            since = null,
            openRounds = 0,
            deliverTo = deliverTo,
        )

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

private const val BILL_PRINTED = "printed"

fun JSONObject.toTables(): List<TableInfo> {
    val array = optJSONArray("tables") ?: return emptyList()
    val list = ArrayList<TableInfo>(array.length())
    for (i in 0 until array.length()) {
        val t = array.optJSONObject(i) ?: continue
        val id = t.optString("tableId")
        if (id.isBlank()) continue
        // A table that is completed and whose bill is printed is done: show it as free.
        // PROPOSED: `billStatus` is "printed" once the desktop has printed the bill. To be confirmed.
        val status = t.optString("status")
        val released = status == TableInfo.STATUS_COMPLETED &&
            t.optString("billStatus").equals(BILL_PRINTED, ignoreCase = true)
        list += TableInfo(
            tableId = id,
            number = t.optString("tableNumber").ifBlank { "?" },
            floor = if (t.isNull("floor")) "" else t.optString("floor").trim(),
            status = if (released) TableInfo.STATUS_AVAILABLE else status,
            total = if (released) 0.0 else t.optDouble("total", 0.0).let { if (it.isNaN()) 0.0 else it },
            since = if (released || t.isNull("since")) null else parseInstant(t.optString("since")),
            openRounds = if (released) 0 else t.optJSONArray("orders")?.length() ?: 0,
            capacity = if (!t.has("capacity") || t.isNull("capacity")) null else t.optInt("capacity").takeIf { it > 0 },
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

internal fun parseInstant(text: String): Instant? =
    runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
