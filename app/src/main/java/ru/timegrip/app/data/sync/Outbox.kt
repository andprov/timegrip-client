package ru.timegrip.app.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.timegrip.app.data.local.MonoStamp
import ru.timegrip.app.data.local.OutboxDao
import ru.timegrip.app.data.local.OutboxEntity
import ru.timegrip.app.data.local.OutboxState
import ru.timegrip.app.data.local.ProjectEntity
import ru.timegrip.app.data.local.TimerEntity

object OpType {
    const val PROJECT_CREATE = "PROJECT_CREATE"
    const val PROJECT_UPDATE = "PROJECT_UPDATE"
    const val PROJECT_DELETE = "PROJECT_DELETE"

    /** A live start: replayed as `POST /timers/start` only while the start is fresh. */
    const val TIMER_START = "TIMER_START"

    /** Stops a server-side running timer, or creates the whole entry if it never got there. */
    const val TIMER_STOP = "TIMER_STOP"
    const val TIMER_CREATE = "TIMER_CREATE"
    const val TIMER_UPDATE = "TIMER_UPDATE"
    const val TIMER_DELETE = "TIMER_DELETE"

    const val USER_TIME_FORMAT = "USER_TIME_FORMAT"
    const val USER_LOCALE = "USER_LOCALE"
}

/** Entity id of account-level operations. */
const val USER_ENTITY_ID = "user"

@Serializable
data class ProjectPayload(
    val name: String,
    val color: String,
    val hourlyRate: String?,
    val roundToHour: Boolean,
    val status: String,
) {
    companion object {
        fun of(project: ProjectEntity) =
            ProjectPayload(project.name, project.color, project.hourlyRate, project.roundToHour, project.status)
    }
}

@Serializable
data class TimerPayload(
    /** Local project id, resolved to the server id when the operation is sent. */
    val projectId: String,
    val startTime: Long,
    val endTime: Long? = null,
    /** See [TimerEntity.startClock]; absent in operations queued by older versions. */
    val startClock: MonoStamp? = null,
    val endClock: MonoStamp? = null,
) {
    companion object {
        fun of(timer: TimerEntity) =
            TimerPayload(timer.projectId, timer.startTime, timer.endTime, timer.startClock, timer.endClock)
    }
}

@Serializable
data class ValuePayload(val value: String)

/**
 * Writes local changes into the outbox. Every method must run inside the same
 * Room transaction as the change to the entity itself, so a record and its
 * pending operation can never disagree.
 *
 * Operations are replayed strictly in order (an entry must be created before
 * it is archived, a project before its entries), so edits are only merged
 * where merging cannot reorder anything.
 */
class Outbox(private val dao: OutboxDao, private val json: Json) {

    suspend fun projectCreated(project: ProjectEntity) =
        insert(OpType.PROJECT_CREATE, project.id, encode(ProjectPayload.of(project)))

    suspend fun projectUpdated(project: ProjectEntity) = enqueueUpdate(
        entityId = project.id,
        hasServerId = project.serverId != null,
        updateType = OpType.PROJECT_UPDATE,
        createTypes = setOf(OpType.PROJECT_CREATE),
        recreateType = OpType.PROJECT_CREATE,
        payload = encode(ProjectPayload.of(project)),
    )

    suspend fun timerStarted(timer: TimerEntity) =
        insert(OpType.TIMER_START, timer.id, encode(TimerPayload.of(timer)))

    suspend fun timerStopped(timer: TimerEntity) =
        insert(OpType.TIMER_STOP, timer.id, encode(TimerPayload.of(timer)))

    suspend fun timerCreated(timer: TimerEntity) =
        insert(OpType.TIMER_CREATE, timer.id, encode(TimerPayload.of(timer)))

    suspend fun timerUpdated(timer: TimerEntity) = enqueueUpdate(
        entityId = timer.id,
        hasServerId = timer.serverId != null,
        updateType = OpType.TIMER_UPDATE,
        createTypes = setOf(OpType.TIMER_CREATE, OpType.TIMER_STOP),
        recreateType = OpType.TIMER_CREATE,
        payload = encode(TimerPayload.of(timer)),
    )

    /**
     * For a record that never reached the server and has nothing in flight,
     * drops all of its operations and returns true: the caller removes the row
     * and the server never hears of it. Otherwise the caller keeps a tombstone
     * and calls [deleted].
     */
    suspend fun dropIfNeverSynced(entityId: String, serverId: String?): Boolean {
        if (serverId != null) return false
        if (dao.getForEntity(entityId).any { it.state == OutboxState.IN_FLIGHT }) return false
        dao.deleteForEntity(entityId)
        return true
    }

    /**
     * Queues the deletion of a record the server knows. Pending edits are
     * pointless now and dropped; a pending stop is kept because the server
     * refuses to delete a running timer.
     */
    suspend fun deleted(entityId: String, deleteType: String, updateType: String) {
        dao.deleteForEntityOfType(entityId, updateType)
        insert(deleteType, entityId, "{}")
    }

    /** Only the latest value of an account setting matters. */
    suspend fun userValueChanged(type: String, value: String) {
        dao.deleteForEntityOfType(USER_ENTITY_ID, type)
        insert(type, USER_ENTITY_ID, encode(ValuePayload(value)))
    }

    private suspend fun enqueueUpdate(
        entityId: String,
        hasServerId: Boolean,
        updateType: String,
        createTypes: Set<String>,
        recreateType: String,
        payload: String,
    ) {
        val ops = dao.getForEntity(entityId)
        if (ops.none { it.state == OutboxState.IN_FLIGHT }) {
            // Editing a record whose creation the server rejected is the fix
            // for it: replace everything queued for it with one fresh creation
            // at the end of the queue.
            val failedCreate = ops.any { it.type in createTypes && it.state == OutboxState.FAILED }
            if (!hasServerId && failedCreate) {
                dao.deleteForEntity(entityId)
                insert(recreateType, entityId, payload)
                return
            }
            val failedUpdates = ops.filter { it.type == updateType && it.state == OutboxState.FAILED }
            if (failedUpdates.isNotEmpty()) {
                failedUpdates.forEach { dao.delete(it.seq) }
                insert(updateType, entityId, payload)
                return
            }
            // Consecutive edits of the same record with nothing queued in
            // between collapse into one request.
            val last = dao.getLast()
            if (last != null && last.entityId == entityId && last.type == updateType &&
                last.state == OutboxState.PENDING
            ) {
                dao.setPayload(last.seq, payload)
                return
            }
        }
        insert(updateType, entityId, payload)
    }

    private suspend fun insert(type: String, entityId: String, payload: String) {
        dao.insert(
            OutboxEntity(
                type = type,
                entityId = entityId,
                payload = payload,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private inline fun <reified T> encode(value: T): String = json.encodeToString(value)
}
