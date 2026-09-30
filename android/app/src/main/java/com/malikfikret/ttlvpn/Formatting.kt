package com.malikfikret.ttlvpn

import android.content.Context
import java.text.NumberFormat
import java.util.Locale

// Every number the app shows goes through here, so it's always in Latin digits (0-9),
// even in Arabic, whose locale defaults to Arabic-Indic digits. Never pass an Int to a
// "%d" string resource: Android formats it with the locale's digits. Format it here and
// pass the String to a "%s" placeholder instead.

// Every formatted value is also wrapped in a Unicode LTR isolate (LRI ... PDI), so it
// always renders left to right as one unit ("7.5 MB", not "MB 7.5"), standalone or inside
// a translated sentence, while the surrounding paragraph keeps its own direction (so in
// Arabic the value still sits on the start side). Isolates are invisible, skipped when
// the paragraph direction is determined, and ignored by screen readers.
private fun ltr(value: String): String = "\u2066$value\u2069"

// Same locale, forced to Latin digits; keeps its decimal separator (Turkish "1,5").
private fun latinDigits(locale: Locale): Locale =
    Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "latn").build()

private fun Context.locale(): Locale = resources.configuration.locales[0]

// Kotlin's Int.toString() is locale-independent, hence always Latin.
fun formatTtl(ttl: Int): String = ltr(ttl.toString())

// 1000-based like Android's own Formatter, with the unit labels translated.
fun formatDataSize(context: Context, bytes: Long): String {
    val units = listOf(R.string.size_bytes, R.string.size_kb, R.string.size_mb, R.string.size_gb)
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val number = NumberFormat.getNumberInstance(latinDigits(context.locale())).apply {
        isGroupingUsed = false
        // Bytes are whole; otherwise one decimal below 100 ("1.5 MB"), none above ("250 MB").
        maximumFractionDigits = if (unit == 0 || value >= 100) 0 else 1
    }.format(value)
    return ltr(context.getString(units[unit], number))
}

// "12:34", or "1:02:03" from one hour on. Separators are universal, digits are Latin.
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return ltr(
        if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    )
}
