package ru.timegrip.app.ui.projects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.timegrip.app.data.repository.ProjectInput
import ru.timegrip.app.data.repository.ProjectRepository
import ru.timegrip.app.data.repository.TimerRepository
import ru.timegrip.app.data.sync.SyncManager
import ru.timegrip.app.domain.BillingFilter
import ru.timegrip.app.domain.Project
import ru.timegrip.app.domain.ProjectColors
import ru.timegrip.app.domain.ProjectStatus
import ru.timegrip.app.domain.StatusFilter
import ru.timegrip.app.domain.parseHourlyRate
import ru.timegrip.app.ui.common.UiMessage

data class ProjectFilters(
    val billing: BillingFilter = BillingFilter.ALL,
    val status: StatusFilter = StatusFilter.ACTIVE,
)

data class ProjectsState(
    val loaded: Boolean = false,
    val filters: ProjectFilters = ProjectFilters(),
    val all: List<Project> = emptyList(),
    val visible: List<Project> = emptyList(),
    val runningProjectId: String? = null,
)

data class ProjectForm(
    val name: String = "",
    val color: String = ProjectColors.DEFAULT,
    val hourlyRate: String = "",
    val roundToHour: Boolean = false,
    val status: ProjectStatus = ProjectStatus.ACTIVE,
) {
    companion object {
        fun of(project: Project) = ProjectForm(
            name = project.name,
            color = project.color,
            hourlyRate = project.hourlyRate?.toPlainString().orEmpty(),
            roundToHour = project.roundToHour,
            status = project.status,
        )
    }
}

sealed interface ProjectEditor {
    data object New : ProjectEditor
    data class Existing(val project: Project) : ProjectEditor
}

/** Web: pages/ProjectsPage.tsx, working on the local copy. */
class ProjectsViewModel(
    private val projectRepository: ProjectRepository,
    timerRepository: TimerRepository,
    private val syncManager: SyncManager,
) : ViewModel() {
    private val filters = MutableStateFlow(ProjectFilters())

    val state: StateFlow<ProjectsState> = combine(
        projectRepository.observeProjects(),
        timerRepository.observeRunning(),
        filters,
    ) { projects, running, f ->
        val byStatus = projects.filter {
            when (f.status) {
                StatusFilter.ALL -> true
                StatusFilter.ACTIVE -> it.status == ProjectStatus.ACTIVE
                StatusFilter.ARCHIVED -> it.status == ProjectStatus.ARCHIVED
            }
        }
        val byBilling = byStatus.filter {
            when (f.billing) {
                BillingFilter.ALL -> true
                BillingFilter.BILLABLE -> it.isBillable
                BillingFilter.NON_BILLABLE -> !it.isBillable
            }
        }
        ProjectsState(
            loaded = true,
            filters = f,
            all = projects,
            visible = byBilling,
            runningProjectId = running?.projectId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsState())

    var editor by mutableStateOf<ProjectEditor?>(null)
        private set
    var editorError by mutableStateOf<UiMessage?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var deleteBlocked by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set

    private val _notices = Channel<String>(Channel.BUFFERED)

    /** Names of edited projects that no longer match the filters. */
    val notices = _notices.receiveAsFlow()

    fun setBilling(value: BillingFilter) = filters.update { it.copy(billing = value) }
    fun setStatus(value: StatusFilter) = filters.update { it.copy(status = value) }

    fun openNew() {
        editorError = null
        deleteBlocked = false
        editor = ProjectEditor.New
    }

    fun openEdit(project: Project) {
        editorError = null
        deleteBlocked = false
        editor = ProjectEditor.Existing(project)
    }

    fun closeEditor() {
        editor = null
    }

    fun save(form: ProjectForm) {
        val target = editor ?: return
        perform {
            val input = ProjectInput(
                name = form.name,
                color = form.color,
                hourlyRate = parseHourlyRate(form.hourlyRate),
                roundToHour = form.roundToHour,
                status = form.status,
            )
            when (target) {
                ProjectEditor.New -> projectRepository.create(input)
                is ProjectEditor.Existing -> {
                    val saved = projectRepository.update(target.project.id, input)
                    val f = filters.value
                    val matchesBilling = f.billing == BillingFilter.ALL ||
                        (f.billing == BillingFilter.BILLABLE) == saved.isBillable
                    val matchesStatus = when (f.status) {
                        StatusFilter.ALL -> true
                        StatusFilter.ACTIVE -> saved.status == ProjectStatus.ACTIVE
                        StatusFilter.ARCHIVED -> saved.status == ProjectStatus.ARCHIVED
                    }
                    if (!matchesBilling || !matchesStatus) _notices.send(saved.name)
                }
            }
            editor = null
        }
    }

    /** Returns false (and explains why) when a running timer blocks the deletion. */
    fun requestDelete(project: Project): Boolean {
        editorError = null
        deleteBlocked = state.value.runningProjectId == project.id
        return !deleteBlocked
    }

    fun delete(project: Project) = perform {
        projectRepository.delete(project.id)
        editor = null
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            syncManager.syncNow()
            refreshing = false
        }
    }

    private fun perform(block: suspend () -> Unit) {
        if (saving) return
        viewModelScope.launch {
            saving = true
            editorError = null
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                editorError = UiMessage.of(failure)
            } finally {
                saving = false
            }
        }
    }
}
