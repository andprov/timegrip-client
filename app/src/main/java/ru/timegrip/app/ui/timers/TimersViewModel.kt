package ru.timegrip.app.ui.timers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.timegrip.app.data.repository.ProjectRepository
import ru.timegrip.app.data.repository.TimerRepository
import ru.timegrip.app.data.sync.SyncManager
import ru.timegrip.app.domain.BillingFilter
import ru.timegrip.app.domain.DateRange
import ru.timegrip.app.domain.Project
import ru.timegrip.app.domain.ProjectStatus
import ru.timegrip.app.domain.TimeEntry
import ru.timegrip.app.domain.sumDurationSeconds
import ru.timegrip.app.domain.toLocalDate
import ru.timegrip.app.domain.zone
import ru.timegrip.app.ui.common.UiMessage
import java.time.LocalDate
import java.time.LocalTime

data class TimerFilters(
    val projectId: String? = null,
    val billing: BillingFilter = BillingFilter.ALL,
    val range: DateRange = DateRange.thisMonth(),
    val showArchived: Boolean = false,
)

data class DayGroup(val date: LocalDate, val totalSeconds: Long, val entries: List<TimeEntry>)

data class TimersState(
    val loaded: Boolean = false,
    val filters: TimerFilters = TimerFilters(),
    val projects: List<Project> = emptyList(),
    val days: List<DayGroup> = emptyList(),
) {
    val projectById: Map<String, Project> = projects.associateBy { it.id }
    val activeProjects: List<Project> get() = projects.filter { it.isActive }
    val filterOptions: List<Project> get() = if (filters.showArchived) projects else activeProjects
}

sealed interface TimerEditor {
    data object New : TimerEditor
    data class Existing(val entry: TimeEntry) : TimerEditor
}

data class TimerForm(
    val projectId: String?,
    val startDate: LocalDate?,
    val startTime: LocalTime?,
    val endDate: LocalDate?,
    val endTime: LocalTime?,
)

/** Web: pages/TimersPage.tsx; the paginated table becomes a list grouped by day. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimersViewModel(
    projectRepository: ProjectRepository,
    private val timerRepository: TimerRepository,
    private val syncManager: SyncManager,
) : ViewModel() {
    private val filters = MutableStateFlow(TimerFilters())

    val state: StateFlow<TimersState> = filters.flatMapLatest { f ->
        combine(timerRepository.observeEntries(f.range), projectRepository.observeProjects()) { entries, projects ->
            val byId = projects.associateBy { it.id }
            val visible = entries.filter { entry ->
                (f.projectId == null || entry.projectId == f.projectId) &&
                    when (f.billing) {
                        BillingFilter.ALL -> true
                        BillingFilter.BILLABLE -> entry.hourlyRate != null
                        BillingFilter.NON_BILLABLE -> entry.hourlyRate == null
                    } &&
                    (f.showArchived || byId[entry.projectId]?.status != ProjectStatus.ARCHIVED)
            }
            TimersState(
                loaded = true,
                filters = f,
                projects = projects,
                days = visible.groupBy { it.start.toLocalDate() }
                    .map { (day, items) -> DayGroup(day, sumDurationSeconds(items.filter { !it.isRunning }), items) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimersState())

    var selection by mutableStateOf<Set<String>>(emptySet())
        private set
    var editor by mutableStateOf<TimerEditor?>(null)
        private set
    var editorError by mutableStateOf<UiMessage?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var bulkError by mutableStateOf<UiMessage?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    init {
        syncManager.requestRangeRefresh(filters.value.range)
    }

    fun setProject(id: String?) = updateFilters { it.copy(projectId = id) }
    fun setBilling(value: BillingFilter) = updateFilters { it.copy(billing = value) }

    fun setRange(range: DateRange) {
        updateFilters { it.copy(range = range) }
        syncManager.requestRangeRefresh(range)
    }

    fun setShowArchived(show: Boolean) = updateFilters { f ->
        val selectedArchived = state.value.projectById[f.projectId]?.status == ProjectStatus.ARCHIVED
        f.copy(showArchived = show, projectId = if (!show && selectedArchived) null else f.projectId)
    }

    private fun updateFilters(change: (TimerFilters) -> TimerFilters) {
        selection = emptySet()
        filters.update(change)
    }

    fun toggleSelection(entry: TimeEntry) {
        if (entry.isRunning) return
        selection = if (entry.id in selection) selection - entry.id else selection + entry.id
    }

    fun selectAll() {
        selection = state.value.days.flatMap { it.entries }.filter { !it.isRunning }.map { it.id }.toSet()
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun openNew() {
        editorError = null
        editor = TimerEditor.New
    }

    fun openEdit(entry: TimeEntry) {
        if (entry.isRunning) return
        editorError = null
        editor = TimerEditor.Existing(entry)
    }

    fun closeEditor() {
        editor = null
    }

    /** Returns false when a required field is missing (the form then shows which). */
    fun save(form: TimerForm): Boolean {
        val target = editor ?: return false
        val projectId = form.projectId
        val start = form.startDate?.let { d -> form.startTime?.let { d.atTime(it) } }?.atZone(zone())?.toInstant()
        var end = form.endDate?.let { d -> form.endTime?.let { d.atTime(it) } }?.atZone(zone())?.toInstant()
        if (projectId == null || start == null || end == null) return false
        // The pickers resolve to minutes, so equal times mean "one minute apart"
        // to the user; a second later satisfies the strict end > start rule.
        if (end == start) end = end.plusSeconds(1)
        perform {
            when (target) {
                TimerEditor.New -> timerRepository.create(projectId, start, end)
                is TimerEditor.Existing -> timerRepository.update(target.entry.id, projectId, start, end)
            }
            editor = null
        }
        return true
    }

    fun delete(entry: TimeEntry) = perform {
        timerRepository.delete(listOf(entry.id))
        editor = null
    }

    fun deleteSelected() {
        val ids = selection
        viewModelScope.launch {
            bulkError = null
            try {
                timerRepository.delete(ids)
                selection = emptySet()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                bulkError = UiMessage.of(failure)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            syncManager.syncNow(filters.value.range)
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
