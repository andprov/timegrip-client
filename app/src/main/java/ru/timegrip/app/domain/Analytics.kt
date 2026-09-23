package ru.timegrip.app.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// Ports of frontend/src/lib/dashboard.ts and frontend/src/lib/report.ts.

const val OTHER_SLICE_KEY = "other"
const val OTHER_COLOR = "#9CA3AF"
private const val MAX_SLICES = 7

/** A day range longer than this is grouped by month instead of by day. */
private const val DAY_GRANULARITY_MAX_DAYS = 62

data class ProjectSlice(
    val key: String,
    val label: String,
    val color: String,
    val totalSeconds: Long,
    val totalAmount: BigDecimal,
)

fun sumDurationSeconds(entries: List<TimeEntry>): Long = entries.sumOf { it.durationSeconds() }

fun sumBillableAmount(entries: List<TimeEntry>): BigDecimal =
    entries.fold(BigDecimal.ZERO) { total, entry -> total + (entry.billableAmount ?: BigDecimal.ZERO) }

/** Per-project totals for every project present in [entries], sorted by time descending. */
fun aggregateProjectTotals(
    entries: List<TimeEntry>,
    projectById: Map<String, Project>,
    unknownProjectLabel: String,
): List<ProjectSlice> {
    val totals = linkedMapOf<String, Pair<Long, BigDecimal>>()
    for (entry in entries) {
        val seconds = entry.durationSeconds()
        val amount = entry.billableAmount ?: BigDecimal.ZERO
        if (seconds <= 0 && amount.signum() <= 0) continue
        val (s, a) = totals[entry.projectId] ?: (0L to BigDecimal.ZERO)
        totals[entry.projectId] = (s + seconds) to (a + amount)
    }
    return totals.map { (projectId, total) ->
        val project = projectById[projectId]
        ProjectSlice(
            key = projectId,
            label = project?.name ?: unknownProjectLabel,
            color = project?.color ?: OTHER_COLOR,
            totalSeconds = total.first,
            totalAmount = total.second,
        )
    }.sortedByDescending { it.totalSeconds }
}

/** Folds every project past the top `MAX_SLICES - 1` into an "Other" slice. */
fun foldIntoChartSlices(totals: List<ProjectSlice>, otherLabel: String): List<ProjectSlice> {
    if (totals.size <= MAX_SLICES) return totals
    val top = totals.take(MAX_SLICES - 1)
    val rest = totals.drop(MAX_SLICES - 1)
    return top + ProjectSlice(
        key = OTHER_SLICE_KEY,
        label = otherLabel,
        color = OTHER_COLOR,
        totalSeconds = rest.sumOf { it.totalSeconds },
        totalAmount = rest.fold(BigDecimal.ZERO) { sum, slice -> sum + slice.totalAmount },
    )
}

fun topByAmount(totals: List<ProjectSlice>): ProjectSlice? =
    totals.fold(null as ProjectSlice?) { best, slice ->
        if (best == null || slice.totalAmount > best.totalAmount) slice else best
    }

enum class TimeGranularity { HOUR, DAY, MONTH }

data class TimeSeriesPoint(val key: String, val label: String, val totalSeconds: Long)

data class TimeSeries(val granularity: TimeGranularity, val points: List<TimeSeriesPoint>)

/**
 * Total time bucketed by hour, day, or month depending on how long the range
 * spans: a single day groups by hour, up to ~2 months by day, longer by month.
 * Falls back to the span of [entries] when the range is open.
 */
fun aggregateTimeSeries(entries: List<TimeEntry>, range: DateRange): TimeSeries {
    val days = entries.map { it.start.toLocalDate() }
    val start = range.from ?: days.minOrNull() ?: return TimeSeries(TimeGranularity.DAY, emptyList())
    val end = range.to ?: days.maxOrNull() ?: return TimeSeries(TimeGranularity.DAY, emptyList())

    val spanDays = ChronoUnit.DAYS.between(start, end) + 1
    return when {
        spanDays <= 1 -> TimeSeries(TimeGranularity.HOUR, aggregateByHour(entries, start))
        spanDays <= DAY_GRANULARITY_MAX_DAYS -> TimeSeries(TimeGranularity.DAY, aggregateByDay(entries, start, end))
        else -> TimeSeries(TimeGranularity.MONTH, aggregateByMonth(entries, start, end))
    }
}

private fun aggregateByHour(entries: List<TimeEntry>, day: LocalDate): List<TimeSeriesPoint> {
    val totals = LongArray(24)
    for (entry in entries) {
        val start = entry.start.atZone(zone())
        if (start.toLocalDate() != day) continue
        totals[start.hour] += entry.durationSeconds()
    }
    return (0 until 24).map { hour ->
        val key = hour.toString().padStart(2, '0')
        TimeSeriesPoint(key = key, label = "$key:00", totalSeconds = totals[hour])
    }
}

private fun aggregateByDay(entries: List<TimeEntry>, from: LocalDate, to: LocalDate): List<TimeSeriesPoint> {
    val totals = entries.groupBy { it.start.toLocalDate() }.mapValues { (_, list) -> sumDurationSeconds(list) }
    return generateSequence(from) { it.plusDays(1) }
        .takeWhile { !it.isAfter(to) }
        .map { TimeSeriesPoint(it.toString(), formatShortDate(it), totals[it] ?: 0) }
        .toList()
}

private fun aggregateByMonth(entries: List<TimeEntry>, from: LocalDate, to: LocalDate): List<TimeSeriesPoint> {
    val totals = entries.groupBy { YearMonth.from(it.start.toLocalDate()) }
        .mapValues { (_, list) -> sumDurationSeconds(list) }
    val last = YearMonth.from(to)
    return generateSequence(YearMonth.from(from)) { it.plusMonths(1) }
        .takeWhile { !it.isAfter(last) }
        .map { TimeSeriesPoint(it.toString(), formatMonthShort(it.atDay(1)), totals[it] ?: 0) }
        .toList()
}

// --- Report ---

enum class GroupBy { NONE, PROJECT, DATE }

enum class ReportColumn { DATE, PROJECT, START, END, DURATION, ROUNDED, AMOUNT }

val DEFAULT_REPORT_COLUMNS: Set<ReportColumn> = setOf(
    ReportColumn.DATE,
    ReportColumn.PROJECT,
    ReportColumn.START,
    ReportColumn.END,
    ReportColumn.DURATION,
    ReportColumn.AMOUNT,
)

data class ReportGroup(
    val key: String,
    val label: String,
    val entries: List<TimeEntry>,
    val totalSeconds: Long,
    val totalAmount: BigDecimal,
)

/** The column a grouping already shows in its group headers, hidden from the rows. */
fun GroupBy.groupedColumn(): ReportColumn? = when (this) {
    GroupBy.NONE -> null
    GroupBy.PROJECT -> ReportColumn.PROJECT
    GroupBy.DATE -> ReportColumn.DATE
}

fun groupEntries(
    entries: List<TimeEntry>,
    groupBy: GroupBy,
    projectById: Map<String, Project>,
    unknownProjectLabel: String,
): List<ReportGroup> {
    fun group(key: String, label: String, items: List<TimeEntry>) =
        ReportGroup(key, label, items, sumDurationSeconds(items), sumBillableAmount(items))

    return when (groupBy) {
        GroupBy.NONE -> listOf(group("all", "", entries))
        GroupBy.PROJECT -> entries.groupBy { it.projectId }
            .map { (projectId, items) ->
                group(projectId, projectById[projectId]?.name ?: unknownProjectLabel, items)
            }
            .sortedWith { a, b -> nameComparator.compare(a.label, b.label) }
        GroupBy.DATE -> entries.groupBy { it.start.toLocalDate() }
            .map { (day, items) -> group(day.toString(), formatDate(day), items) }
            .sortedByDescending { it.key }
    }
}
