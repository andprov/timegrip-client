package ru.timegrip.app.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.timegrip.app.data.local.AppDatabase
import ru.timegrip.app.data.local.OutboxState
import ru.timegrip.app.data.sync.OpType
import ru.timegrip.app.data.sync.SyncEngine
import ru.timegrip.app.data.sync.SyncManager
import ru.timegrip.app.data.sync.USER_ENTITY_ID
import java.time.Instant

/** A change the server rejected, with enough context to recognize it. */
data class SyncIssue(
    val entityId: String,
    val opType: String,
    val errorCode: String?,
    val errorMessage: String?,
    val projectName: String?,
    val projectColor: String?,
    val start: Instant?,
    val end: Instant?,
)

class SyncIssuesRepository(
    private val db: AppDatabase,
    private val syncEngine: SyncEngine,
    private val syncManager: SyncManager,
) {
    private val outboxDao = db.outboxDao()
    private val projectDao = db.projectDao()
    private val timerDao = db.timerDao()

    fun observeIssues(): Flow<List<SyncIssue>> = outboxDao.observeFailed().map { ops ->
        ops.map { op ->
            val timer = timerDao.get(op.entityId)
            val project = projectDao.get(timer?.projectId ?: op.entityId)
            SyncIssue(
                entityId = op.entityId,
                opType = op.type,
                errorCode = op.errorCode,
                errorMessage = op.errorMessage,
                projectName = project?.name,
                projectColor = project?.color,
                start = timer?.startTime?.let(Instant::ofEpochMilli),
                end = timer?.endTime?.let(Instant::ofEpochMilli),
            )
        }
    }

    /** Sends the record's rejected changes again (e.g. after fixing the cause on the web). */
    suspend fun retry(entityId: String) {
        outboxDao.retryEntity(entityId)
        syncManager.requestSync()
    }

    /** Drops the record's unsent changes and goes back to the server's version of it. */
    suspend fun discard(entityId: String) {
        val restoreFromServer = db.withTransaction {
            val ops = outboxDao.getForEntity(entityId)
            if (ops.any { it.state == OutboxState.IN_FLIGHT }) return@withTransaction false
            outboxDao.deleteForEntity(entityId)
            if (entityId == USER_ENTITY_ID) return@withTransaction true

            val timer = timerDao.get(entityId)
            if (timer != null) {
                if (timer.serverId == null) timerDao.delete(entityId) else timerDao.upsert(timer.copy(deleted = false))
                return@withTransaction timer.serverId != null
            }
            val project = projectDao.get(entityId) ?: return@withTransaction false
            if (project.serverId == null) {
                // Its entries could never reach the server either.
                for (child in timerDao.getByProject(entityId)) {
                    outboxDao.deleteForEntity(child.id)
                    timerDao.delete(child.id)
                }
                projectDao.delete(entityId)
                false
            } else {
                projectDao.upsert(project.copy(deleted = false))
                true
            }
        }
        if (restoreFromServer && syncManager.isOnline.value) {
            runCatching {
                if (entityId == USER_ENTITY_ID) {
                    syncEngine.refreshUser()
                } else {
                    syncEngine.refreshRecord(entityId)
                }
            }
        }
        syncManager.requestSync()
    }

    companion object {
        val TIMER_OPS = setOf(
            OpType.TIMER_START,
            OpType.TIMER_STOP,
            OpType.TIMER_CREATE,
            OpType.TIMER_UPDATE,
            OpType.TIMER_DELETE,
        )
    }
}
