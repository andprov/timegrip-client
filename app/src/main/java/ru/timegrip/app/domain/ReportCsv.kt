package ru.timegrip.app.domain

import java.math.BigDecimal

/** Port of frontend/src/lib/reportExport.ts: the same rows the web app downloads. */
object ReportCsv {
    data class Labels(
        val columns: Map<ReportColumn, String>,
        val total: String,
        val entries: String,
    )

    fun build(
        groups: List<ReportGroup>,
        columns: List<ReportColumn>,
        groupBy: GroupBy,
        projectById: Map<String, Project>,
        timeFormat: TimeFormat,
        labels: Labels,
    ): String {
        val rows = mutableListOf(columns.map { labels.columns.getValue(it) })
        for (group in groups) {
            if (groupBy != GroupBy.NONE) {
                val summary = "${group.label} (${group.entries.size} ${labels.entries}, " +
                    "${formatClock(group.totalSeconds)}, ${formatAmountPlain(group.totalAmount)})"
                rows += listOf(summary) + List(maxOf(0, columns.size - 1)) { "" }
            }
            for (entry in group.entries) {
                rows += columns.map { cellText(it, entry, projectById, timeFormat) }
            }
        }
        val allEntries = groups.flatMap { it.entries }
        rows += totalRow(columns, sumDurationSeconds(allEntries), sumBillableAmount(allEntries), labels.total)

        // The BOM makes Excel open the UTF-8 file with the right encoding.
        return "\uFEFF" + rows.joinToString("\r\n") { row -> row.joinToString(",") { escape(it) } }
    }

    fun cellText(
        column: ReportColumn,
        entry: TimeEntry,
        projectById: Map<String, Project>,
        timeFormat: TimeFormat,
    ): String = when (column) {
        ReportColumn.DATE -> formatDate(entry.start)
        ReportColumn.PROJECT -> projectById[entry.projectId]?.name ?: "—"
        ReportColumn.START -> formatTime(entry.start, timeFormat)
        ReportColumn.END -> entry.end?.let { end ->
            if (end.toLocalDate() == entry.start.toLocalDate()) {
                formatTime(end, timeFormat)
            } else {
                formatDateTime(end, timeFormat)
            }
        } ?: ""
        ReportColumn.DURATION -> formatClock(entry.durationSeconds())
        ReportColumn.ROUNDED -> if (entry.roundToHour) "R" else ""
        ReportColumn.AMOUNT -> formatAmountPlain(entry.billableAmount)
    }

    private fun totalRow(
        columns: List<ReportColumn>,
        totalSeconds: Long,
        totalAmount: BigDecimal,
        totalLabel: String,
    ): List<String> {
        val firstMetric = columns.indexOfFirst { it == ReportColumn.DURATION || it == ReportColumn.AMOUNT }
        return columns.mapIndexed { index, column ->
            when {
                firstMetric == -1 || index < firstMetric -> if (index == 0) totalLabel else ""
                column == ReportColumn.DURATION -> formatClock(totalSeconds)
                column == ReportColumn.AMOUNT -> formatAmountPlain(totalAmount)
                else -> ""
            }
        }
    }

    private fun escape(value: String): String =
        if (value.any { it == '"' || it == ',' || it == '\r' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
