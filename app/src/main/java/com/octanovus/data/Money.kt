package com.octanovus.data

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Currency and locale from GET /api/me. Defaults to the desktop's own defaults (INR, en-IN). */
object Money {
    @Volatile
    private var currencyCode = "INR"

    @Volatile
    private var localeTag = "en-IN"

    fun configure(currencyCode: String?, localeTag: String?) {
        if (!currencyCode.isNullOrBlank()) this.currencyCode = currencyCode
        if (!localeTag.isNullOrBlank()) this.localeTag = localeTag
    }

    fun format(amount: Double): String {
        val locale = runCatching { Locale.forLanguageTag(localeTag) }.getOrDefault(Locale("en", "IN"))
        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }
        val symbol = runCatching { Currency.getInstance(currencyCode).getSymbol(locale) }.getOrDefault(currencyCode)
        return symbol + number.format(amount)
    }
}
