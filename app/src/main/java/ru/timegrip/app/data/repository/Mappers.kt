package ru.timegrip.app.data.repository

import ru.timegrip.app.data.local.ProjectWithSync
import ru.timegrip.app.data.local.TimerWithSync
import ru.timegrip.app.data.remote.SessionDto
import ru.timegrip.app.data.remote.UserDto
import ru.timegrip.app.data.sync.SyncEngine
import ru.timegrip.app.domain.AppLocale
import ru.timegrip.app.domain.Project
import ru.timegrip.app.domain.ProjectStatus
import ru.timegrip.app.domain.Session
import ru.timegrip.app.domain.SyncMark
import ru.timegrip.app.domain.TimeEntry
import ru.timegrip.app.domain.TimeFormat
import ru.timegrip.app.domain.User
import java.time.Instant

private fun syncMark(pending: Int, failed: Int): SyncMark = when {
    failed > 0 -> SyncMark.FAILED
    pending > 0 -> SyncMark.PENDING
    else -> SyncMark.SYNCED
}

fun ProjectWithSync.toDomain(): Project = Project(
    id = project.id,
    name = project.name,
    color = project.color,
    hourlyRate = project.hourlyRate?.toBigDecimalOrNull(),
    roundToHour = project.roundToHour,
    status = ProjectStatus.fromWire(project.status),
    syncMark = syncMark(pendingOps, failedOps),
)

fun TimerWithSync.toDomain(): TimeEntry = TimeEntry(
    id = timer.id,
    projectId = timer.projectId,
    start = Instant.ofEpochMilli(timer.startTime),
    end = timer.endTime?.let(Instant::ofEpochMilli),
    hourlyRate = timer.hourlyRate?.toBigDecimalOrNull(),
    roundToHour = timer.roundToHour,
    billableAmount = timer.billableAmount?.toBigDecimalOrNull(),
    syncMark = syncMark(pendingOps, failedOps),
)

fun UserDto.toDomain(): User = User(
    id = id,
    email = email,
    isActive = isActive,
    timeFormat = TimeFormat.fromWire(timeFormat),
    locale = AppLocale.fromWire(locale),
)

fun SessionDto.toDomain(): Session = Session(
    id = id,
    userAgent = userAgent,
    ipAddress = ipAddress,
    createdAt = SyncEngine.parseInstant(createdAt),
)
