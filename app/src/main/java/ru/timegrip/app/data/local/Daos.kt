package ru.timegrip.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query(
        "SELECT p.*, " +
            "(SELECT COUNT(*) FROM outbox o WHERE o.entityId = p.id) AS pendingOps, " +
            "(SELECT COUNT(*) FROM outbox o WHERE o.entityId = p.id AND o.state = 'FAILED') AS failedOps " +
            "FROM projects p WHERE p.deleted = 0",
    )
    fun observeAll(): Flow<List<ProjectWithSync>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE serverId = :serverId")
    suspend fun getByServerId(serverId: String): ProjectEntity?

    @Query("SELECT * FROM projects")
    suspend fun getAll(): List<ProjectEntity>

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: String, serverId: String)

    @Query("UPDATE projects SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TimerDao {
    @Query(
        "SELECT t.*, " +
            "(SELECT COUNT(*) FROM outbox o WHERE o.entityId = t.id) AS pendingOps, " +
            "(SELECT COUNT(*) FROM outbox o WHERE o.entityId = t.id AND o.state = 'FAILED') AS failedOps " +
            "FROM timers t WHERE t.deleted = 0 " +
            "AND (:from IS NULL OR t.startTime >= :from) AND (:to IS NULL OR t.startTime <= :to) " +
            "ORDER BY t.startTime DESC",
    )
    fun observeRange(from: Long?, to: Long?): Flow<List<TimerWithSync>>

    @Query("SELECT * FROM timers WHERE deleted = 0 AND endTime IS NULL ORDER BY startTime DESC")
    fun observeRunning(): Flow<List<TimerEntity>>

    @Query("SELECT * FROM timers WHERE deleted = 0 AND endTime IS NULL ORDER BY startTime DESC")
    suspend fun getRunning(): List<TimerEntity>

    @Query("SELECT * FROM timers WHERE id = :id")
    suspend fun get(id: String): TimerEntity?

    @Query("SELECT * FROM timers WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<TimerEntity>

    @Query("SELECT * FROM timers WHERE serverId = :serverId")
    suspend fun getByServerId(serverId: String): TimerEntity?

    @Query("SELECT * FROM timers WHERE projectId = :projectId")
    suspend fun getByProject(projectId: String): List<TimerEntity>

    /** Server-backed rows whose start falls in the range (null bounds are open). */
    @Query(
        "SELECT * FROM timers WHERE serverId IS NOT NULL " +
            "AND (:from IS NULL OR startTime >= :from) AND (:to IS NULL OR startTime <= :to)",
    )
    suspend fun getSyncedInRange(from: Long?, to: Long?): List<TimerEntity>

    /** Same rule as the backend's `has_overlapping_timer`: a running entry overlaps everything after its start. */
    @Query(
        "SELECT COUNT(*) FROM timers WHERE deleted = 0 AND id != :excludeId " +
            "AND (endTime IS NULL OR endTime > :start) AND startTime < :end",
    )
    suspend fun countOverlapping(start: Long, end: Long, excludeId: String): Int

    @Upsert
    suspend fun upsert(timer: TimerEntity)

    @Query("UPDATE timers SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("DELETE FROM timers WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(op: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE seq = :seq")
    suspend fun get(seq: Long): OutboxEntity?

    @Query("SELECT * FROM outbox ORDER BY seq")
    suspend fun getAll(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE state = 'PENDING' ORDER BY seq")
    suspend fun getPending(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE entityId = :entityId ORDER BY seq")
    suspend fun getForEntity(entityId: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY seq DESC LIMIT 1")
    suspend fun getLast(): OutboxEntity?

    @Query("SELECT DISTINCT entityId FROM outbox")
    suspend fun getEntityIds(): List<String>

    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed FROM outbox")
    fun observeCounts(): Flow<OutboxCounts>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("SELECT * FROM outbox WHERE state = 'FAILED' ORDER BY seq")
    fun observeFailed(): Flow<List<OutboxEntity>>

    @Query("UPDATE outbox SET state = :state, errorCode = NULL, errorMessage = NULL WHERE seq = :seq")
    suspend fun setState(seq: Long, state: String)

    @Query("UPDATE outbox SET payload = :payload, state = 'PENDING', errorCode = NULL, errorMessage = NULL WHERE seq = :seq")
    suspend fun setPayload(seq: Long, payload: String)

    @Query("UPDATE outbox SET state = 'FAILED', errorCode = :code, errorMessage = :message WHERE seq = :seq")
    suspend fun setFailed(seq: Long, code: String?, message: String?)

    @Query("UPDATE outbox SET state = 'PENDING', errorCode = NULL, errorMessage = NULL WHERE entityId = :entityId AND state = 'FAILED'")
    suspend fun retryEntity(entityId: String)

    /** Requests cut off by a crash are replayed; see SyncEngine for how duplicates are recognized. */
    @Query("UPDATE outbox SET state = 'PENDING' WHERE state = 'IN_FLIGHT'")
    suspend fun resetInFlight()

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun delete(seq: Long)

    @Query("DELETE FROM outbox WHERE entityId = :entityId")
    suspend fun deleteForEntity(entityId: String)

    @Query("DELETE FROM outbox WHERE entityId = :entityId AND type = :type AND state != 'IN_FLIGHT'")
    suspend fun deleteForEntityOfType(entityId: String, type: String)
}
