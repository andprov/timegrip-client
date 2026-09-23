package ru.timegrip.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** An inclusive range of local days; `null` bounds mean "all dates". */
data class DateRange(val from: LocalDate?, val to: LocalDate?) {
    val isAll: Boolean get() = from == null && to == null

    val startInstant: Instant? get() = from?.atStartOfDay(zone())?.toInstant()

    val endInstant: Instant? get() = to?.atTime(LocalTime.MAX)?.atZone(zone())?.toInstant()

    companion object {
        val ALL = DateRange(null, null)

        fun thisMonth(today: LocalDate = LocalDate.now()): DateRange =
            DateRange(today.withDayOfMonth(1), today.with(TemporalAdjusters.lastDayOfMonth()))
    }
}

enum class DateRangePreset {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    LAST_WEEK,
    THIS_MONTH,
    LAST_MONTH,
    THIS_YEAR,
    LAST_YEAR;

    fun range(today: LocalDate = LocalDate.now()): DateRange = when (this) {
        TODAY -> DateRange(today, today)
        YESTERDAY -> today.minusDays(1).let { DateRange(it, it) }
        THIS_WEEK -> today.mondayOf().let { DateRange(it, it.plusDays(6)) }
        LAST_WEEK -> today.mondayOf().minusWeeks(1).let { DateRange(it, it.plusDays(6)) }
        THIS_MONTH -> DateRange.thisMonth(today)
        LAST_MONTH -> DateRange.thisMonth(today.minusMonths(1).withDayOfMonth(1))
        THIS_YEAR -> DateRange(today.withDayOfYear(1), today.withMonth(12).withDayOfMonth(31))
        LAST_YEAR -> today.minusYears(1).let {
            DateRange(it.withDayOfYear(1), it.withMonth(12).withDayOfMonth(31))
        }
    }

    companion object {
        fun matching(range: DateRange, today: LocalDate = LocalDate.now()): DateRangePreset? =
            entries.firstOrNull { it.range(today) == range }
    }
}

private fun LocalDate.mondayOf(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
