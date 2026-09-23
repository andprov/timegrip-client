package ru.timegrip.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class AnalyticsTest {
    private fun entry(projectId: String, day: LocalDate, hour: Int, minutes: Long, amount: String? = null) =
        day.atTime(LocalTime.of(hour, 0)).atZone(zone()).toInstant().let { start ->
            TimeEntry(
                id = "$projectId-$day-$hour",
                projectId = projectId,
                start = start,
                end = start.plusSeconds(minutes * 60),
                hourlyRate = amount?.let { BigDecimal("1") },
                roundToHour = false,
                billableAmount = amount?.let(::BigDecimal),
            )
        }

    private val day = LocalDate.of(2026, 9, 21)

    @Test
    fun oneDayIsGroupedByHour() {
        val series = aggregateTimeSeries(listOf(entry("a", day, 9, 30)), DateRange(day, day))
        assertEquals(TimeGranularity.HOUR, series.granularity)
        assertEquals(24, series.points.size)
        assertEquals(1800L, series.points[9].totalSeconds)
    }

    @Test
    fun monthIsGroupedByDayAndYearByMonth() {
        assertEquals(TimeGranularity.DAY, aggregateTimeSeries(emptyList(), DateRange.thisMonth(day)).granularity)
        val year = DateRangePreset.THIS_YEAR.range(day)
        val series = aggregateTimeSeries(listOf(entry("a", day, 9, 60)), year)
        assertEquals(TimeGranularity.MONTH, series.granularity)
        assertEquals(12, series.points.size)
        assertEquals(3600L, series.points[8].totalSeconds)
    }

    @Test
    fun foldsSmallProjectsIntoOther() {
        val entries = (1..9).map { entry("p$it", day, it, it.toLong()) }
        val totals = aggregateProjectTotals(entries, emptyMap(), "?")
        assertEquals("p9", totals.first().key)
        val slices = foldIntoChartSlices(totals, "Other")
        assertEquals(7, slices.size)
        assertEquals(OTHER_SLICE_KEY, slices.last().key)
        assertEquals((1 + 2 + 3) * 60L, slices.last().totalSeconds)
    }

    @Test
    fun csvMatchesTheWebExport() {
        val project = Project("a", "Site, v2", "#F44336", BigDecimal("50"), false, ProjectStatus.ACTIVE)
        val entries = listOf(entry("a", day, 9, 90, "75.00"))
        val columns = listOf(ReportColumn.PROJECT, ReportColumn.DURATION, ReportColumn.AMOUNT)
        val csv = ReportCsv.build(
            groups = groupEntries(entries, GroupBy.NONE, mapOf("a" to project), "?"),
            columns = columns,
            groupBy = GroupBy.NONE,
            projectById = mapOf("a" to project),
            timeFormat = TimeFormat.H24,
            labels = ReportCsv.Labels(columns.associateWith { it.name }, "Total", "entries"),
        )
        assertEquals("﻿PROJECT,DURATION,AMOUNT\r\n\"Site, v2\",01:30,75.00\r\nTotal,01:30,75.00", csv)
    }

    @Test
    fun formatsLikeTheWeb() {
        assertEquals("1 234 567.50", formatAmount(BigDecimal("1234567.5")))
        assertEquals("–", formatAmount(null))
        assertEquals("125:03", formatClock(125 * 3600 + 3 * 60 + 59))
        assertEquals("01:02:03", formatElapsed(3723))
    }
}
