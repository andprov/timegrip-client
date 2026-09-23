package ru.timegrip.app.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.timegrip.app.data.local.AppDatabase
import ru.timegrip.app.data.local.TimerEntity
import ru.timegrip.app.data.local.TimerWithSync
import ru.timegrip.app.data.sync.OpType
import ru.timegrip.app.data.sync.Outbox
import ru.timegrip.app.data.sync.SyncManager
import ru.timegrip.app.domain.DateRange
import ru.timegrip.app.domain.DomainException
import ru.timegrip.app.domain.ProjectStatus
import ru.timegrip.app.domain.TimeEntry
import ru.timegrip.app.domain.calculateBillableAmount
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/** The earliest start the API accepts (`MIN_TIMER_START` on the backend). */
private val MIN_TIMER_START: Instant = ZonedDateTime.of(1900, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

class TimerRepository(
    private val db: AppDatabase,
    private val outbox: Outbox,
    private val syncManager: SyncManager,
) {
    private val timerDao = db.timerDao()
    private val projectDao = db.projectDao()

    fun observeEntries(range: DateRange): Flow<List<TimeEntry>> =
        timerDao.observeRange(range.startInstant?.toEpochMilli(), range.endInstant?.toEpochMilli())
            .map { rows -> rows.map(TimerWithSync::toDomain) }

    /** The most recently started running timer, if any. */
    fun observeRunning(): Flow<TimeEntry?> = timerDao.observeRunning().map { rows ->
        rows.firstOrNull()?.let { TimerWithSync(it, 0, 0).toDomain() }
    }

    suspend fun start(projectId: String) {
        db.withTransaction {
            if (timerDao.getRunning().isNotEmpty()) throw DomainException("timer_already_running")
            val project = projectDao.get(projectId)?.takeUnless { it.deleted }
                ?: throw DomainException("project_not_found")
            if (project.status == ProjectStatus.ARCHIVED.wire) throw DomainException("project_archived")
            val timer = TimerEntity(
                id = UUID.randomUUID().toString(),
                serverId = null,
                projectId = projectId,
                startTime = System.currentTimeMillis(),
                endTime = null,
                hourlyRate = project.hourlyRate,
                roundToHour = project.roundToHour,
                billableAmount = null,
            )
            timerDao.upsert(timer)
            outbox.timerStarted(timer)
        }
        syncManager.requestSync()
    }

    /**
     * Stops every running timer (normally just one; two can meet when a timer
     * started offline here and another one started on the web).
     */
    suspend fun stop() {
        db.withTransaction {
            val now = System.currentTimeMillis()
            for (running in timerDao.getRunning()) {
                val end = maxOf(now, running.startTime + 1000)
                val stopped = running.copy(endTime = end, billableAmount = amountOf(running.copy(endTime = end)))
                timerDao.upsert(stopped)
                outbox.timerStopped(stopped)
            }
        }
        syncManager.requestSync()
    }

    suspend fun create(projectId: String, start: Instant, end: Instant) {
        db.withTransaction {
            val project = projectDao.get(projectId)?.takeUnless { it.deleted }
                ?: throw DomainException("project_not_found")
            if (project.status == ProjectStatus.ARCHIVED.wire) throw DomainException("project_archived")
            val id = UUID.randomUUID().toString()
            val range = validRange(start, end, excludeId = id)
            val timer = TimerEntity(
                id = id,
                serverId = null,
                projectId = projectId,
                startTime = range.first,
                endTime = range.second,
                hourlyRate = project.hourlyRate,
                roundToHour = project.roundToHour,
                billableAmount = null,
            ).let { it.copy(billableAmount = amountOf(it)) }
            timerDao.upsert(timer)
            outbox.timerCreated(timer)
        }
        syncManager.requestSync()
    }

    suspend fun update(id: String, projectId: String, start: Instant, end: Instant) {
        db.withTransaction {
            val existing = timerDao.get(id)?.takeUnless { it.deleted } ?: throw DomainException("timer_not_found")
            if (existing.endTime == null) throw DomainException("timer_running")
            var updated = existing
            if (projectId != existing.projectId) {
                val project = projectDao.get(projectId)?.takeUnless { it.deleted }
                    ?: throw DomainException("project_not_found")
                if (project.status == ProjectStatus.ARCHIVED.wire) throw DomainException("project_archived")
                // Moving an entry re-snapshots the rate, as the server does.
                updated = updated.copy(
                    projectId = projectId,
                    hourlyRate = project.hourlyRate,
                    roundToHour = project.roundToHour,
                )
            }
            val range = validRange(start, end, excludeId = id)
            updated = updated.copy(startTime = range.first, endTime = range.second)
            updated = updated.copy(billableAmount = amountOf(updated))
            timerDao.upsert(updated)
            outbox.timerUpdated(updated)
        }
        syncManager.requestSync()
    }

    suspend fun delete(ids: Collection<String>) {
        db.withTransaction {
            for (timer in timerDao.getMany(ids.toList())) {
                if (timer.endTime == null) throw DomainException("timer_running")
                if (outbox.dropIfNeverSynced(timer.id, timer.serverId)) {
                    timerDao.delete(timer.id)
                } else {
                    timerDao.markDeleted(timer.id)
                    outbox.deleted(timer.id, OpType.TIMER_DELETE, OpType.TIMER_UPDATE)
                }
            }
        }
        syncManager.requestSync()
    }

    /** Checks the rules the server enforces, so an offline entry is not rejected later. */
    private suspend fun validRange(start: Instant, end: Instant, excludeId: String): Pair<Long, Long> {
        val now = Instant.now()
        if (start.isBefore(MIN_TIMER_START)) throw DomainException("start_time_too_early")
        if (start.isAfter(now)) throw DomainException("start_time_in_future")
        if (end.isAfter(now)) throw DomainException("end_time_in_future")
        if (!end.isAfter(start)) throw DomainException("end_time_before_start")
        val startMs = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        if (timerDao.countOverlapping(startMs, endMs, excludeId) > 0) throw DomainException("timer_overlap")
        return startMs to endMs
    }

    private fun amountOf(timer: TimerEntity): String? {
        val end = timer.endTime ?: return null
        return calculateBillableAmount(
            durationSeconds = (end - timer.startTime) / 1000,
            hourlyRate = timer.hourlyRate?.toBigDecimalOrNull(),
            roundToHour = timer.roundToHour,
        )?.toPlainString()
    }
}
