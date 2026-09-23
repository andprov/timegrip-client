package ru.timegrip.app.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records are keyed by a local id so they can be created offline. [serverId]
 * is filled once the server has accepted the record; rows downloaded from the
 * server reuse the server id as the local one.
 *
 * [deleted] marks a tombstone: the row is gone for the UI but kept until its
 * deletion reaches the server, so the sync can still resolve its server id.
 */
@Entity(tableName = "projects", indices = [Index(value = ["serverId"], unique = true)])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val serverId: String?,
    val name: String,
    val color: String,
    val hourlyRate: String?,
    val roundToHour: Boolean,
    val status: String,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "timers",
    indices = [
        Index(value = ["serverId"], unique = true),
        Index(value = ["projectId"]),
        Index(value = ["startTime"]),
    ],
)
data class TimerEntity(
    @PrimaryKey val id: String,
    val serverId: String?,
    /** Local id of the project. */
    val projectId: String,
    val startTime: Long,
    val endTime: Long?,
    /** Rate and rounding copied from the project when the entry was created. */
    val hourlyRate: String?,
    val roundToHour: Boolean,
    val billableAmount: String?,
    val deleted: Boolean = false,
)

/**
 * One local change waiting to be replayed against the API, in [seq] order.
 * [payload] is a snapshot of what the change was when the user made it.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["entityId"])])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val type: String,
    val entityId: String,
    val payload: String,
    val state: String = OutboxState.PENDING,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
)

object OutboxState {
    const val PENDING = "PENDING"
    const val IN_FLIGHT = "IN_FLIGHT"
    const val FAILED = "FAILED"
}

data class ProjectWithSync(
    @Embedded val project: ProjectEntity,
    val pendingOps: Int,
    val failedOps: Int,
)

data class TimerWithSync(
    @Embedded val timer: TimerEntity,
    val pendingOps: Int,
    val failedOps: Int,
)

data class OutboxCounts(val total: Int, val failed: Int)
