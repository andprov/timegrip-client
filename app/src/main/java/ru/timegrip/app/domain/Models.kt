package ru.timegrip.app.domain

import java.math.BigDecimal
import java.time.Instant

enum class ProjectStatus(val wire: String) {
    ACTIVE("active"),
    ARCHIVED("archived");

    companion object {
        fun fromWire(value: String): ProjectStatus =
            entries.firstOrNull { it.wire == value } ?: ACTIVE
    }
}

enum class TimeFormat(val wire: String) {
    H12("12h"),
    H24("24h");

    companion object {
        fun fromWire(value: String): TimeFormat =
            entries.firstOrNull { it.wire == value } ?: H24
    }
}

enum class AppLocale(val wire: String, val displayName: String) {
    EN("en", "English"),
    RU("ru", "Русский");

    companion object {
        fun fromWire(value: String?): AppLocale =
            entries.firstOrNull { it.wire == value } ?: EN
    }
}

enum class ThemePreference { LIGHT, DARK, SYSTEM }

enum class BillingFilter { ALL, BILLABLE, NON_BILLABLE }

enum class StatusFilter { ALL, ACTIVE, ARCHIVED }

/** The palette the API accepts for projects (see `ProjectColor` on the backend). */
object ProjectColors {
    const val DEFAULT = "#9E9E9E"

    val all = listOf(
        "#F44336",
        "#FF9800",
        "#FFEB3B",
        "#4CAF50",
        "#00CCCC",
        "#2196F3",
        "#3F51B5",
        "#9C27B0",
        "#E91E63",
        "#9E9E9E",
    )
}

data class User(
    val id: String,
    val email: String,
    val isActive: Boolean,
    val timeFormat: TimeFormat,
    val locale: AppLocale,
)

/** Whether a local record still has changes waiting for the server. */
enum class SyncMark { SYNCED, PENDING, FAILED }

data class Project(
    val id: String,
    val name: String,
    val color: String,
    val hourlyRate: BigDecimal?,
    val roundToHour: Boolean,
    val status: ProjectStatus,
    val syncMark: SyncMark = SyncMark.SYNCED,
) {
    val isBillable: Boolean get() = hourlyRate != null
    val isActive: Boolean get() = status == ProjectStatus.ACTIVE
}

data class TimeEntry(
    val id: String,
    val projectId: String,
    val start: Instant,
    val end: Instant?,
    val hourlyRate: BigDecimal?,
    val roundToHour: Boolean,
    val billableAmount: BigDecimal?,
    val syncMark: SyncMark = SyncMark.SYNCED,
) {
    val isRunning: Boolean get() = end == null

    fun durationSeconds(now: Instant = Instant.now()): Long =
        maxOf(0L, ((end ?: now).toEpochMilli() - start.toEpochMilli()) / 1000)
}

data class Session(
    val id: Long,
    val userAgent: String?,
    val ipAddress: String?,
    val createdAt: Instant,
)

/**
 * A rule broken by user input, detected locally before anything reaches the
 * server. [code] uses the same identifiers as the API's error `code` field, so
 * both are translated by the same lookup.
 */
class DomainException(val code: String) : Exception(code)
