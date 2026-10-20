package com.octanovus.data

import org.json.JSONObject

/** One menu item from GET /api/menu-items. Only the fields the phone uses. */
data class MenuItem(
    val id: String,
    val name: String,
    val price: Double,
    val categoryName: String,
    val isVeg: Boolean,
    val description: String?,
    val imageUrl: String?,
    val sortOrder: Int,
    val taxName: String?,
    val taxRatePercent: Double,
    val priceIncludesTax: Boolean,
    val searchKey: String,
) {
    /** "+ GST 5%" when the price excludes tax. Null when tax is already in the price. */
    val taxLabel: String?
        get() = if (priceIncludesTax || taxName.isNullOrBlank() || taxRatePercent <= 0.0) null
        else "+ $taxName ${formatPercent(taxRatePercent)}%"
}

/** The menu reply. [lastError] is set when the desktop's last refresh failed, so the list may be stale. */
data class MenuData(val items: List<MenuItem>, val lastError: String?)

fun JSONObject.toMenu(): MenuData {
    val array = optJSONArray("items")
    val raw = ArrayList<MenuItem>(array?.length() ?: 0)
    if (array != null) {
        for (i in 0 until array.length()) {
            val m = array.optJSONObject(i) ?: continue
            val id = m.text("id") ?: continue
            raw += MenuItem(
                id = id,
                name = m.text("name") ?: "Item",
                price = m.optDouble("price", 0.0).let { if (it.isNaN()) 0.0 else it },
                categoryName = m.text("category_name") ?: "Other",
                isVeg = m.optBoolean("is_veg", false),
                description = m.text("description"),
                imageUrl = m.text("image_url"),
                sortOrder = m.optInt("sort_order", 0),
                taxName = m.text("tax_name"),
                taxRatePercent = m.optDouble("tax_rate_percent", 0.0).let { if (it.isNaN()) 0.0 else it },
                priceIncludesTax = m.optBoolean("price_includes_tax", false),
                searchKey = m.optString("search_key")
            )
        }
    }
    // Categories keep the order they first appear in. Items follow sort_order inside a category.
    val categoryOrder = raw.map { it.categoryName }.distinct()
    val sorted = raw.sortedWith(
        compareBy<MenuItem>({ categoryOrder.indexOf(it.categoryName) }, { it.sortOrder }, { it.name.lowercase() })
    )
    return MenuData(sorted, text("lastError"))
}

/** A string field, or null when it is missing, JSON null or blank. */
private fun JSONObject.text(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }

private fun formatPercent(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
