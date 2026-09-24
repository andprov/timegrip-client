package ru.timegrip.app.data.sync

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.timegrip.app.data.local.AppDatabase
import ru.timegrip.app.data.local.OutboxEntity
import ru.timegrip.app.data.local.OutboxState
import ru.timegrip.app.data.local.ProjectEntity
import ru.timegrip.app.data.local.TimerEntity
import ru.timegrip.app.data.prefs.SessionStore
import ru.timegrip.app.data.remote.ApiException
import ru.timegrip.app.data.remote.LocaleBody
import ru.timegrip.app.data.remote.ProjectCreateBody
import ru.timegrip.app.data.remote.ProjectDto
import ru.timegrip.app.data.remote.TimeFormatBody
import ru.timegrip.app.data.remote.TimeGripApi
import ru.timegrip.app.data.remote.TimerCreateBody
import ru.timegrip.app.data.remote.TimerDto
import ru.timegrip.app.data.remote.TimerStartBody
import ru.timegrip.app.data.remote.UserDto
import ru.timegrip.app.data.remote.apiCall
import ru.timegrip.app.domain.AppLocale
import ru.timegrip.app.domain.ProjectStatus
import ru.timegrip.app.domain.TimeFormat
import ru.timegrip.app.domain.User
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Replays the outbox against the API (push) and refreshes the local copy from
 * it (pull). Callers must not run two engines at once; [SyncManager] holds the
 * lock.
 *
 * The API has no client-generated ids and stamps `start`/`stop` with the
 * server clock, so timers get special handling:
 * - a timer started while online is started on the server right away; one
 *   started offline stays local until it is stopped and is then uploaded as a
 *   manual entry with its real start and end;
 * - a stop replayed late is sent as a stop followed by a correction of the end
 *   time to the moment the user actually pressed Stop.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val api: TimeGripApi,
    private val sessionStore: SessionStore,
    private val json: Json,
) {
    private val projectDao = db.projectDao()
    private val timerDao = db.timerDao()
    private val outboxDao = db.outboxDao()

    /** A dependency (a project, an earlier creation) has not reached the server yet. */
    private class Blocked : Exception()

    // --- push ---

    /**
     * Sends every processable operation. Rejected operations are marked
     * failed and block later operations of the same record until the user
     * retries or discards them. Throws on network, server-side and session
     * errors, leaving the rest of the queue for the next attempt.
     */
    suspend fun push() {
        outboxDao.resetInFlight()
        var progressed = true
        while (progressed) {
            progressed = false
            val blocked = outboxDao.getAll()
                .filter { it.state == OutboxState.FAILED }
                .mapTo(mutableSetOf()) { it.entityId }
            for (candidate in outboxDao.getPending()) {
                val op = outboxDao.get(candidate.seq) ?: continue
                if (op.state != OutboxState.PENDING || op.entityId in blocked) continue

                outboxDao.setState(op.seq, OutboxState.IN_FLIGHT)
                try {
                    execute(op)
                    progressed = true
                } catch (_: Blocked) {
                    outboxDao.setState(op.seq, OutboxState.PENDING)
                    blocked += op.entityId
                } catch (error: ApiException) {
                    if (error.isTransient || error.status == 401 || error.code == "access_denied") {
                        outboxDao.setState(op.seq, OutboxState.PENDING)
                        throw error
                    }
                    outboxDao.setFailed(op.seq, error.code, error.detail)
                    blocked += op.entityId
                } catch (error: Throwable) {
                    // Network errors, cancellation: the request may be retried as is.
                    outboxDao.setState(op.seq, OutboxState.PENDING)
                    throw error
                }
            }
        }
    }

    private suspend fun execute(op: OutboxEntity) {
        when (op.type) {
            OpType.PROJECT_CREATE -> createProject(op)
            OpType.PROJECT_UPDATE -> updateProject(op)
            OpType.PROJECT_DELETE -> deleteProject(op)
            OpType.TIMER_START -> startTimer(op)
            OpType.TIMER_STOP -> stopTimer(op)
            OpType.TIMER_CREATE -> createTimer(op, decode(op.payload))
            OpType.TIMER_UPDATE -> updateTimer(op)
            OpType.TIMER_DELETE -> deleteTimer(op)
            OpType.USER_TIME_FORMAT -> updateUser(op) { api.updateTimeFormat(TimeFormatBody(it)) }
            OpType.USER_LOCALE -> updateUser(op) { api.updateLocale(LocaleBody(it)) }
            else -> outboxDao.delete(op.seq)
        }
    }

    /**
     * Creating a project is not idempotent and the server allows duplicate
     * names, so a retry after a lost response would create a second project.
     * Before creating, the engine looks for an identical server project that
     * no local record owns yet and adopts it instead.
     */
    private suspend fun createProject(op: OutboxEntity) {
        val payload = decode<ProjectPayload>(op.payload)
        val created = findSameProject(payload) ?: call {
            api.createProject(ProjectCreateBody(payload.name, payload.color, payload.hourlyRate, payload.roundToHour))
        }
        db.withTransaction {
            // The server id is stored with the creation itself, so nothing that
            // fails afterwards can send the creation again.
            assignProjectServerId(op.entityId, created.id)
            val alone = isLastOp(op)
            outboxDao.delete(op.seq)
            if (payload.status == ProjectStatus.ARCHIVED.wire && created.status != payload.status) {
                // Creation always makes a project active; the archiving goes out as
                // its own update (a later queued update carries the status anyway).
                if (alone) {
                    outboxDao.insert(
                        OutboxEntity(
                            type = OpType.PROJECT_UPDATE,
                            entityId = op.entityId,
                            payload = op.payload,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            } else if (alone) {
                applyProject(op.entityId, created, onlyIfExists = true)
            }
        }
    }

    private suspend fun findSameProject(payload: ProjectPayload): ProjectDto? =
        fetchAllProjects().firstOrNull { dto ->
            dto.name == payload.name &&
                dto.color == payload.color &&
                dto.roundToHour == payload.roundToHour &&
                sameRate(dto.hourlyRate, payload.hourlyRate) &&
                projectDao.getByServerId(dto.id) == null
        }

    /** "20" and "20.00" are the same rate. */
    private fun sameRate(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        val x = a.toBigDecimalOrNull() ?: return a == b
        val y = b.toBigDecimalOrNull() ?: return false
        return x.compareTo(y) == 0
    }

    private suspend fun updateProject(op: OutboxEntity) {
        val serverId = projectServerId(op.entityId)
        val payload = decode<ProjectPayload>(op.payload)
        val body = buildJsonObject {
            put("name", payload.name)
            put("color", payload.color)
            // An explicit null clears the rate; an omitted field would keep it.
            put("hourly_rate", payload.hourlyRate?.let { JsonPrimitive(it) } ?: JsonNull)
            put("round_to_hour", payload.roundToHour)
            put("status", payload.status)
        }
        val result = try {
            call { api.updateProject(serverId, body) }
        } catch (error: ApiException) {
            if (error.status != 404) throw error
            // Deleted on another device: the deletion wins.
            db.withTransaction { removeProjectLocally(op.entityId) }
            return
        }
        db.withTransaction {
            if (isLastOp(op)) applyProject(op.entityId, result, onlyIfExists = true)
            outboxDao.delete(op.seq)
        }
    }

    private suspend fun deleteProject(op: OutboxEntity) {
        val serverId = projectDao.get(op.entityId)?.serverId
        if (serverId != null) {
            try {
                call { api.deleteProject(serverId) }
            } catch (error: ApiException) {
                if (error.status != 404) throw error
            }
        }
        db.withTransaction { removeProjectLocally(op.entityId) }
    }

    private suspend fun startTimer(op: OutboxEntity) {
        val timer = timerDao.get(op.entityId)
        val payload = decode<TimerPayload>(op.payload)
        // Started offline (or long ago): the server would stamp the wrong
        // start, so the entry stays local until it is stopped.
        if (timer == null || timer.serverId != null ||
            System.currentTimeMillis() - payload.startTime > START_GRACE_MS
        ) {
            outboxDao.delete(op.seq)
            return
        }
        val projectServerId = projectServerId(payload.projectId)
        val started = try {
            call { api.startTimer(TimerStartBody(projectServerId)) }
        } catch (error: ApiException) {
            if (error.code != "timer_already_running") throw error
            // Either our own start whose response got lost, or a timer started
            // elsewhere; in the latter case ours stays local until stopped.
            val running = fetchRunning()
            if (running == null || running.projectId != projectServerId ||
                abs(parseInstant(running.startTime).toEpochMilli() - payload.startTime) > START_GRACE_MS ||
                timerDao.getByServerId(running.id) != null
            ) {
                outboxDao.delete(op.seq)
                return
            }
            running
        }
        db.withTransaction {
            assignTimerServerId(op.entityId, started.id)
            outboxDao.delete(op.seq)
        }
    }

    private suspend fun stopTimer(op: OutboxEntity) {
        val timer = timerDao.get(op.entityId)
        val payload = decode<TimerPayload>(op.payload)
        if (timer == null || payload.endTime == null) {
            outboxDao.delete(op.seq)
            return
        }
        val serverId = timer.serverId ?: return createTimer(op, payload)

        val running = fetchRunning()
        val result: TimerDto = if (running?.id == serverId) {
            val stopped = call { api.stopTimer() }
            correctTimes(stopped, payload)
        } else {
            // Stopped or deleted on another device meanwhile: theirs wins.
            try {
                call { api.timer(serverId) }
            } catch (error: ApiException) {
                if (error.status != 404) throw error
                db.withTransaction { removeTimerLocally(op.entityId) }
                return
            }
        }
        db.withTransaction {
            if (isLastOp(op)) applyTimer(op.entityId, result, onlyIfExists = true)
            outboxDao.delete(op.seq)
        }
    }

    /** After a late stop, moves the server's times to what the user recorded. */
    private suspend fun correctTimes(stopped: TimerDto, payload: TimerPayload): TimerDto {
        val serverEnd = stopped.endTime?.let { parseInstant(it).toEpochMilli() } ?: return stopped
        val serverStart = parseInstant(stopped.startTime).toEpochMilli()
        // Never ask for a time past the server's clock.
        val end = minOf(payload.endTime ?: serverEnd, serverEnd)
        val start = minOf(payload.startTime, end - 1000)
        val projectServerId = projectServerId(payload.projectId)
        if (abs(end - serverEnd) < TIME_TOLERANCE_MS && abs(start - serverStart) < TIME_TOLERANCE_MS &&
            projectServerId == stopped.projectId
        ) {
            return stopped
        }
        return call { api.updateTimer(stopped.id, timerPatch(projectServerId, start, end)) }
    }

    private suspend fun createTimer(op: OutboxEntity, payload: TimerPayload) {
        val end = payload.endTime ?: run {
            outboxDao.delete(op.seq)
            return
        }
        val projectServerId = projectServerId(payload.projectId)
        suspend fun create(start: Long, end: Long) = api.createTimer(TimerCreateBody(projectServerId, isoString(start), isoString(end)))
        val created = try {
            call { create(payload.startTime, end) }
        } catch (error: ApiException) {
            when {
                // A retry of a creation whose response was lost overlaps itself.
                error.code == "timer_overlap" -> findSameEntry(projectServerId, payload.startTime, end) ?: throw error
                else -> {
                    val (start, shiftedEnd) = behindServerClock(error, payload.startTime, end) ?: throw error
                    call { create(start, shiftedEnd) }
                }
            }
        }
        db.withTransaction {
            // The row is only gone if its project was deleted meanwhile; the
            // server drops the entry along with the project.
            if (timerDao.get(op.entityId) != null) {
                assignTimerServerId(op.entityId, created.id)
                if (isLastOp(op)) applyTimer(op.entityId, created, onlyIfExists = true)
            }
            outboxDao.delete(op.seq)
        }
    }

    private suspend fun updateTimer(op: OutboxEntity) {
        val timer = timerDao.get(op.entityId) ?: run {
            outboxDao.delete(op.seq)
            return
        }
        val serverId = timer.serverId ?: throw missingServerId(op)
        val payload = decode<TimerPayload>(op.payload)
        val end = payload.endTime ?: run {
            outboxDao.delete(op.seq)
            return
        }
        val projectServerId = projectServerId(payload.projectId)
        suspend fun update(start: Long, end: Long) = api.updateTimer(serverId, timerPatch(projectServerId, start, end))
        val result = try {
            try {
                call { update(payload.startTime, end) }
            } catch (error: ApiException) {
                val (start, shiftedEnd) = behindServerClock(error, payload.startTime, end) ?: throw error
                call { update(start, shiftedEnd) }
            }
        } catch (error: ApiException) {
            if (error.status != 404) throw error
            db.withTransaction { removeTimerLocally(op.entityId) }
            return
        }
        db.withTransaction {
            if (isLastOp(op)) applyTimer(op.entityId, result, onlyIfExists = true)
            outboxDao.delete(op.seq)
        }
    }

    private suspend fun deleteTimer(op: OutboxEntity) {
        val serverId = timerDao.get(op.entityId)?.serverId
        if (serverId != null) {
            try {
                call { api.deleteTimer(serverId) }
            } catch (error: ApiException) {
                if (error.status != 404) throw error
            }
        }
        db.withTransaction { removeTimerLocally(op.entityId) }
    }

    private suspend fun updateUser(op: OutboxEntity, send: suspend (String) -> UserDto) {
        val value = decode<ValuePayload>(op.payload).value
        val dto = call { send(value) }
        db.withTransaction { outboxDao.delete(op.seq) }
        storeUser(dto)
    }

    // --- pull ---

    /**
     * Downloads projects, the running timer and the entries that start within
     * [from]..[to] (open bounds allowed), and reconciles the local copy.
     * Records with queued changes are left alone: local edits win until they
     * are sent.
     */
    suspend fun pull(from: Instant?, to: Instant?) {
        val user = call { api.me() }
        storeUser(user)
        if (!user.isActive) return

        val projects = fetchAllProjects()
        db.withTransaction { reconcileProjects(projects) }

        val running = fetchRunning()
        val timers = fetchAllTimers(from, to)
        db.withTransaction { reconcileTimers(timers, running, from, to) }
        refreshStaleRunning(running)
    }

    private suspend fun reconcileProjects(remote: List<ProjectDto>) {
        val protected = protectedIds()
        val remoteIds = remote.mapTo(mutableSetOf()) { it.id }
        for (dto in remote) {
            val local = projectDao.getByServerId(dto.id)
            if (local != null && (local.id in protected || local.deleted)) continue
            applyProject(local?.id ?: dto.id, dto)
        }
        for (local in projectDao.getAll()) {
            val serverId = local.serverId ?: continue
            if (serverId in remoteIds || local.id in protected || local.deleted) continue
            removeProjectLocally(local.id)
        }
    }

    private suspend fun reconcileTimers(remote: List<TimerDto>, running: TimerDto?, from: Instant?, to: Instant?) {
        val protected = protectedIds()
        val all = if (running != null && remote.none { it.id == running.id }) remote + running else remote
        for (dto in all) {
            val local = timerDao.getByServerId(dto.id)
            if (local != null && (local.id in protected || local.deleted)) continue
            // Entries of a project deleted locally (not yet on the server) stay hidden.
            if (projectDao.getByServerId(dto.projectId)?.deleted != false) continue
            applyTimer(local?.id ?: dto.id, dto)
        }
        val remoteIds = remote.mapTo(mutableSetOf()) { it.id }
        running?.let { remoteIds += it.id }
        for (local in timerDao.getSyncedInRange(from?.toEpochMilli(), to?.toEpochMilli())) {
            if (local.serverId in remoteIds || local.id in protected || local.deleted) continue
            timerDao.delete(local.id)
        }
    }

    /** Local copies of server timers that still look running but no longer are. */
    private suspend fun refreshStaleRunning(running: TimerDto?) {
        for (local in timerDao.getRunning()) {
            val serverId = local.serverId ?: continue
            if (serverId == running?.id) continue
            val dto = try {
                call { api.timer(serverId) }
            } catch (error: ApiException) {
                if (error.status != 404) throw error
                null
            }
            db.withTransaction {
                if (local.id in protectedIds()) return@withTransaction
                if (dto == null) timerDao.delete(local.id) else applyTimer(local.id, dto)
            }
        }
    }

    private suspend fun fetchAllProjects(): List<ProjectDto> {
        val items = mutableListOf<ProjectDto>()
        var page = 1
        while (true) {
            val result = call { api.projects(page, TimeGripApi.MAX_PAGE_SIZE) }
            items += result.items
            if (result.items.size < TimeGripApi.MAX_PAGE_SIZE || items.size >= result.total) return items
            page++
        }
    }

    private suspend fun fetchAllTimers(from: Instant?, to: Instant?): List<TimerDto> {
        val items = mutableListOf<TimerDto>()
        var page = 1
        while (true) {
            val result = call {
                api.timers(
                    page = page,
                    pageSize = TimeGripApi.MAX_PAGE_SIZE,
                    dateFrom = from?.toString(),
                    dateTo = to?.toString(),
                    includeArchivedProjects = true,
                )
            }
            items += result.items
            if (result.items.size < TimeGripApi.MAX_PAGE_SIZE || items.size >= result.total) return items
            page++
        }
    }

    private suspend fun fetchRunning(): TimerDto? {
        val element = call { api.runningTimer() }
        if (element is JsonNull) return null
        return json.decodeFromJsonElement(TimerDto.serializer(), element)
    }

    private suspend fun findSameEntry(projectServerId: String, start: Long, end: Long): TimerDto? {
        val candidates = call {
            api.timers(
                page = 1,
                pageSize = TimeGripApi.MAX_PAGE_SIZE,
                dateFrom = Instant.ofEpochMilli(start - 1000).toString(),
                dateTo = Instant.ofEpochMilli(start + 1000).toString(),
                includeArchivedProjects = true,
            )
        }.items
        return candidates.firstOrNull { dto ->
            dto.projectId == projectServerId &&
                abs(parseInstant(dto.startTime).toEpochMilli() - start) < 1000 &&
                dto.endTime?.let { abs(parseInstant(it).toEpochMilli() - end) < 1000 } == true &&
                timerDao.getByServerId(dto.id) == null
        }
    }

    // --- local bookkeeping ---

    private suspend fun protectedIds(): Set<String> = outboxDao.getEntityIds().toSet()

    private suspend fun isLastOp(op: OutboxEntity): Boolean =
        outboxDao.getForEntity(op.entityId).all { it.seq == op.seq }

    private suspend fun projectServerId(localId: String): String {
        val project = projectDao.get(localId)
        return project?.serverId ?: run {
            if (project != null && outboxDao.getForEntity(localId).isNotEmpty()) throw Blocked()
            throw ApiException(404, "project_not_found", null)
        }
    }

    private suspend fun missingServerId(op: OutboxEntity): Exception =
        if (outboxDao.getForEntity(op.entityId).any { it.seq < op.seq }) {
            Blocked()
        } else {
            ApiException(404, "timer_not_found", null)
        }

    private suspend fun assignProjectServerId(localId: String, serverId: String) {
        projectDao.getByServerId(serverId)?.takeIf { it.id != localId }?.let { projectDao.delete(it.id) }
        projectDao.setServerId(localId, serverId)
    }

    private suspend fun assignTimerServerId(localId: String, serverId: String) {
        timerDao.getByServerId(serverId)?.takeIf { it.id != localId }?.let { timerDao.delete(it.id) }
        val timer = timerDao.get(localId) ?: return
        timerDao.upsert(timer.copy(serverId = serverId))
    }

    private suspend fun applyProject(localId: String, dto: ProjectDto, onlyIfExists: Boolean = false) {
        val existing = projectDao.get(localId)
        if (existing == null && onlyIfExists) return
        projectDao.upsert(
            ProjectEntity(
                id = localId,
                serverId = dto.id,
                name = dto.name,
                color = dto.color,
                hourlyRate = dto.hourlyRate,
                roundToHour = dto.roundToHour,
                status = dto.status,
                deleted = existing?.deleted ?: false,
            ),
        )
    }

    private suspend fun applyTimer(localId: String, dto: TimerDto, onlyIfExists: Boolean = false) {
        val existing = timerDao.get(localId)
        if (existing == null && onlyIfExists) return
        val projectId = projectDao.getByServerId(dto.projectId)?.id ?: existing?.projectId ?: return
        timerDao.upsert(
            TimerEntity(
                id = localId,
                serverId = dto.id,
                projectId = projectId,
                startTime = parseInstant(dto.startTime).toEpochMilli(),
                endTime = dto.endTime?.let { parseInstant(it).toEpochMilli() },
                hourlyRate = dto.hourlyRate,
                roundToHour = dto.roundToHour,
                billableAmount = dto.billableAmount,
                deleted = existing?.deleted ?: false,
            ),
        )
    }

    /** Mirrors the server's cascade: a project takes its entries with it. */
    private suspend fun removeProjectLocally(projectId: String) {
        for (timer in timerDao.getByProject(projectId)) removeTimerLocally(timer.id)
        outboxDao.deleteForEntity(projectId)
        projectDao.delete(projectId)
    }

    private suspend fun removeTimerLocally(timerId: String) {
        outboxDao.deleteForEntity(timerId)
        timerDao.delete(timerId)
    }

    suspend fun refreshUser() = storeUser(call { api.me() })

    /** Saves the profile from the server, keeping account settings still queued locally. */
    suspend fun storeUser(dto: UserDto) {
        val pending = outboxDao.getForEntity(USER_ENTITY_ID).map { it.type }.toSet()
        val cached = sessionStore.user
        // Settings changed offline stay as the user left them until they are sent.
        val user = User(
            id = dto.id,
            email = dto.email,
            isActive = dto.isActive,
            timeFormat = if (OpType.USER_TIME_FORMAT in pending && cached != null) {
                cached.timeFormat
            } else {
                TimeFormat.fromWire(dto.timeFormat)
            },
            locale = if (OpType.USER_LOCALE in pending && cached != null) cached.locale else AppLocale.fromWire(dto.locale),
        )
        if (sessionStore.state.value.accessToken != null) sessionStore.saveUser(user)
    }

    /**
     * Replaces the local copy of one record with the server's, after the user
     * discarded their unsent changes to it. Best effort: without a network the
     * next pull does the same.
     */
    suspend fun refreshRecord(localId: String) {
        timerDao.get(localId)?.serverId?.let { serverId ->
            val dto = try {
                call { api.timer(serverId) }
            } catch (error: ApiException) {
                if (error.status != 404) throw error
                null
            }
            db.withTransaction {
                if (localId in protectedIds()) return@withTransaction
                if (dto == null) timerDao.delete(localId) else applyTimer(localId, dto)
            }
            return
        }
        projectDao.get(localId)?.serverId?.let { serverId ->
            val dto = try {
                call { api.project(serverId) }
            } catch (error: ApiException) {
                if (error.status != 404) throw error
                null
            }
            db.withTransaction {
                if (localId in protectedIds()) return@withTransaction
                if (dto == null) removeProjectLocally(localId) else applyProject(localId, dto)
            }
        }
    }

    private fun timerPatch(projectServerId: String, start: Long, end: Long): JsonObject = buildJsonObject {
        put("project_id", projectServerId)
        put("start_time", isoString(start))
        put("end_time", isoString(end))
    }

    private suspend inline fun <T> call(block: () -> T): T = apiCall(json, block)

    private inline fun <reified T> decode(payload: String): T = json.decodeFromString(payload)

    companion object {
        /** How stale a start may be and still be replayed as a live `POST /timers/start`. */
        const val START_GRACE_MS = 10_000L

        /** Server times closer than this to the recorded ones are left as they are. */
        const val TIME_TOLERANCE_MS = 2_000L

        /**
         * The times of an entry the server refused as lying in the future, moved back
         * onto the server's clock; null for any other refusal. Recorded times come
         * from this device's clock, and one running ahead of the server's puts the
         * end of a timer just stopped "in the future". The whole entry moves by the
         * difference, so it keeps its length. The server's clock is known to the
         * second and rounded down, so the new end is never ahead of it.
         */
        internal fun behindServerClock(error: ApiException, start: Long, end: Long): Pair<Long, Long>? {
            if (error.code != "end_time_in_future" && error.code != "start_time_in_future") return null
            val serverNow = error.serverTime?.toEpochMilli() ?: return null
            val ahead = end - serverNow
            if (ahead <= 0) return null
            return start - ahead to end - ahead
        }

        fun isoString(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

        fun parseInstant(value: String): Instant =
            runCatching { OffsetDateTime.parse(value).toInstant() }
                .getOrElse { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }
    }
}
