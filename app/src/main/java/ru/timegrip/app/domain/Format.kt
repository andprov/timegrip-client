package ru.timegrip.app.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val SHORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM")
private val TIME_24H = DateTimeFormatter.ofPattern("HH:mm")

fun zone(): ZoneId = ZoneId.systemDefault()

fun Instant.toLocalDate(): LocalDate = atZone(zone()).toLocalDate()

private fun pad(value: Long): String = value.toString().padStart(2, '0')

/** `HH:MM` of a total, hours not capped at 24 (web: formatSecondsAsClock). */
fun formatClock(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return "${pad(hours)}:${pad(minutes)}"
}

/** `HH:MM:SS` of a running timer. */
fun formatElapsed(totalSeconds: Long): String {
    val seconds = maxOf(0, totalSeconds)
    return "${pad(seconds / 3600)}:${pad((seconds % 3600) / 60)}:${pad(seconds % 60)}"
}

fun formatCountdown(totalSeconds: Int): String =
    "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"

/** `1 234.50`, or an en dash when there is no amount. */
fun formatAmount(value: BigDecimal?): String {
    if (value == null) return "–"
    val scaled = value.setScale(2, RoundingMode.HALF_UP)
    val plain = scaled.abs().toPlainString()
    val (intPart, fracPart) = plain.split('.').let { it[0] to it.getOrElse(1) { "00" } }
    val grouped = intPart.reversed().chunked(3).joinToString(" ").reversed()
    val sign = if (scaled.signum() < 0) "-" else ""
    return "$sign$grouped.$fracPart"
}

fun formatAmountPlain(value: BigDecimal?): String =
    value?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: "–"

fun formatDate(date: LocalDate): String = date.format(DATE_FORMAT)

fun formatDate(instant: Instant): String = formatDate(instant.toLocalDate())

fun formatShortDate(date: LocalDate): String = date.format(SHORT_DATE_FORMAT)

fun formatTime(instant: Instant, timeFormat: TimeFormat, locale: Locale = Locale.getDefault()): String {
    val formatter = when (timeFormat) {
        TimeFormat.H24 -> TIME_24H
        TimeFormat.H12 -> DateTimeFormatter.ofPattern("hh:mm a", locale)
    }
    return instant.atZone(zone()).format(formatter)
}

fun formatDateTime(instant: Instant, timeFormat: TimeFormat): String =
    "${formatDate(instant)} ${formatTime(instant, timeFormat)}"

fun formatMonthShort(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(DateTimeFormatter.ofPattern("LLL", locale))

/** `1h 30m` between two instants, rounded to the minute (web: formatDurationBetween). */
fun formatDurationBetween(start: Instant, end: Instant, hourUnit: String, minuteUnit: String): String {
    val totalMinutes = maxOf(0L, Math.round((end.toEpochMilli() - start.toEpochMilli()) / 60000.0))
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "$minutes$minuteUnit"
        minutes == 0L -> "$hours$hourUnit"
        else -> "$hours$hourUnit $minutes$minuteUnit"
    }
}

// A fixed collator: the device locale must not change how Latin and Cyrillic
// names interleave (web: lib/sort.ts).
private val nameCollator: Collator = Collator.getInstance(Locale.ENGLISH)

val nameComparator: Comparator<String> = Comparator { a, b -> nameCollator.compare(a, b) }

fun List<Project>.sortedByName(): List<Project> = sortedWith { a, b -> nameComparator.compare(a.name, b.name) }
