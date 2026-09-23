package ru.timegrip.app.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.timegrip.app.data.local.AppDatabase
import ru.timegrip.app.data.local.ProjectEntity
import ru.timegrip.app.data.sync.OpType
import ru.timegrip.app.data.sync.Outbox
import ru.timegrip.app.data.sync.SyncManager
import ru.timegrip.app.domain.DomainException
import ru.timegrip.app.domain.Project
import ru.timegrip.app.domain.ProjectColors
import ru.timegrip.app.domain.ProjectStatus
import ru.timegrip.app.domain.sortedByName
import java.math.BigDecimal
import java.util.UUID

data class ProjectInput(
    val name: String,
    val color: String,
    val hourlyRate: BigDecimal?,
    val roundToHour: Boolean,
    val status: ProjectStatus = ProjectStatus.ACTIVE,
)

/** Projects are read from and written to the local database; the outbox carries writes to the server. */
class ProjectRepository(
    private val db: AppDatabase,
    private val outbox: Outbox,
    private val syncManager: SyncManager,
) {
    private val projectDao = db.projectDao()
    private val timerDao = db.timerDao()

    fun observeProjects(): Flow<List<Project>> =
        projectDao.observeAll().map { rows -> rows.map { it.toDomain() }.sortedByName() }

    suspend fun create(input: ProjectInput) {
        val project = ProjectEntity(
            id = UUID.randomUUID().toString(),
            serverId = null,
            name = validName(input.name),
            color = validColor(input.color),
            hourlyRate = input.hourlyRate?.toPlainString(),
            // Rounding means nothing without a rate; the server stores it as false too.
            roundToHour = input.roundToHour && input.hourlyRate != null,
            status = ProjectStatus.ACTIVE.wire,
        )
        db.withTransaction {
            projectDao.upsert(project)
            outbox.projectCreated(project)
        }
        syncManager.requestSync()
    }

    /** Returns the saved project. */
    suspend fun update(id: String, input: ProjectInput): Project {
        val saved = db.withTransaction {
            val existing = projectDao.get(id)?.takeUnless { it.deleted } ?: throw DomainException("project_not_found")
            if (input.status == ProjectStatus.ARCHIVED && existing.status != ProjectStatus.ARCHIVED.wire &&
                hasRunningTimer(id)
            ) {
                throw DomainException("project_has_running_timer")
            }
            val project = existing.copy(
                name = validName(input.name),
                color = validColor(input.color),
                hourlyRate = input.hourlyRate?.toPlainString(),
                roundToHour = input.roundToHour && input.hourlyRate != null,
                status = input.status.wire,
            )
            projectDao.upsert(project)
            outbox.projectUpdated(project)
            project
        }
        syncManager.requestSync()
        return Project(
            id = saved.id,
            name = saved.name,
            color = saved.color,
            hourlyRate = saved.hourlyRate?.toBigDecimalOrNull(),
            roundToHour = saved.roundToHour,
            status = ProjectStatus.fromWire(saved.status),
        )
    }

    /** Deletes the project and, like the server, all of its time entries. */
    suspend fun delete(id: String) {
        db.withTransaction {
            val project = projectDao.get(id) ?: return@withTransaction
            if (hasRunningTimer(id)) throw DomainException("project_has_running_timer")
            // The server cascades the entries; locally they go right away.
            for (timer in timerDao.getByProject(id)) {
                if (outbox.dropIfNeverSynced(timer.id, timer.serverId) || timer.serverId != null) {
                    db.outboxDao().deleteForEntity(timer.id)
                    timerDao.delete(timer.id)
                } else {
                    timerDao.markDeleted(timer.id)
                }
            }
            if (outbox.dropIfNeverSynced(id, project.serverId)) {
                projectDao.delete(id)
            } else {
                projectDao.markDeleted(id)
                outbox.deleted(id, OpType.PROJECT_DELETE, OpType.PROJECT_UPDATE)
            }
        }
        syncManager.requestSync()
    }

    suspend fun hasRunningTimer(projectId: String): Boolean =
        timerDao.getRunning().any { it.projectId == projectId }

    private fun validName(name: String): String =
        name.trim().ifEmpty { throw DomainException("name_required") }

    private fun validColor(color: String): String =
        if (color in ProjectColors.all) color else throw DomainException("invalid_project_color")
}
