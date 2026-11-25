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
    /** The item's search keywords (`search_key` on the desktop). Searched together with the name. */
    val searchKey: String? = null,
    /** `category_sort_order` on the desktop. Lowest first; items without one come last. */
    val categorySortOrder: Int = Int.MAX_VALUE,
) {
    /** "GST 5%". Null when the item has no tax. */
    val taxTitle: String?
        get() = if (taxName.isNullOrBlank() || taxRatePercent <= 0.0) null
        else "$taxName ${formatPercent(taxRatePercent)}%"

    /** "+ GST 5%" when the price excludes tax. Null when tax is already in the price. */
    val taxLabel: String?
        get() = if (priceIncludesTax) null else taxTitle?.let { "+ $it" }
}

/** The menu reply. [lastError] is set when the desktop's last refresh failed, so the list may be stale. */
data class MenuData(val items: List<MenuItem>, val lastError: String?)

fun JSONObject.toMenu(): MenuData {
    val array = optJSONArray("items")
    val raw = ArrayList<MenuItem>(array?.length() ?: 0)
    if (array != null) {
        for (i in 0 until array.length()) {
            val m = array.optJSONObject(i) ?: continue
            // Inactive or unavailable items are not shown.
            if (!m.optBoolean("is_active", true) || !m.optBoolean("is_available", true)) continue
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
                searchKey = m.text("search_key"),
                categorySortOrder = if (m.isNull("category_sort_order") || !m.has("category_sort_order")) Int.MAX_VALUE
                else m.optInt("category_sort_order", Int.MAX_VALUE),
            )
        }
    }
    // Categories follow category_sort_order (lowest first, none last), then their name.
    // Items follow their name inside a category.
    val categoryOrder = raw
        .groupBy { it.categoryName }
        .entries
        .sortedWith(compareBy({ e -> e.value.minOf { it.categorySortOrder } }, { e -> e.key.lowercase() }))
        .map { it.key }
    val sorted = raw.sortedWith(
        compareBy<MenuItem>({ categoryOrder.indexOf(it.categoryName) }, { it.name.lowercase() })
    )
    return MenuData(sorted, text("lastError"))
}

/** A string field, or null when it is missing, JSON null or blank. */
private fun JSONObject.text(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }

private fun formatPercent(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
