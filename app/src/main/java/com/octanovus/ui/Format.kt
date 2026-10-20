package com.octanovus.ui

import java.text.NumberFormat
import java.util.Locale

/** Rupee amount with Indian digit grouping, for example ₹1,120 or ₹640.5. */
internal fun formatMoney(amount: Double): String {
    val format = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }
    return "₹" + format.format(amount)
}
