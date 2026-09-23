package ru.timegrip.app.ui.timers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.timegrip.app.R
import ru.timegrip.app.domain.Project
import ru.timegrip.app.domain.TimeEntry
import ru.timegrip.app.domain.TimeFormat
import ru.timegrip.app.domain.formatAmount
import ru.timegrip.app.domain.formatClock
import ru.timegrip.app.domain.formatDate
import ru.timegrip.app.domain.formatDateTime
import ru.timegrip.app.domain.formatDurationBetween
import ru.timegrip.app.domain.formatTime
import ru.timegrip.app.domain.toLocalDate
import ru.timegrip.app.domain.zone
import ru.timegrip.app.ui.common.BillingFilterChip
import ru.timegrip.app.ui.common.ColorDot
import ru.timegrip.app.ui.common.ConfirmDialog
import ru.timegrip.app.ui.common.DateField
import ru.timegrip.app.ui.common.DateRangeFilterChip
import ru.timegrip.app.ui.common.ErrorBanner
import ru.timegrip.app.ui.common.FabOnList
import ru.timegrip.app.ui.common.FilterBar
import ru.timegrip.app.ui.common.FilterBarBottomGap
import ru.timegrip.app.ui.common.FilterBarHeight
import ru.timegrip.app.ui.common.FullScreenDialog
import ru.timegrip.app.ui.common.LocalAppContainer
import ru.timegrip.app.ui.common.MessageCard
import ru.timegrip.app.ui.common.ProjectFilterChip
import ru.timegrip.app.ui.common.ProjectPickerWindow
import ru.timegrip.app.ui.common.SectionLabel
import ru.timegrip.app.ui.common.SyncMarkIcon
import ru.timegrip.app.ui.common.TimeField
import ru.timegrip.app.ui.common.appViewModel
import ru.timegrip.app.ui.common.rememberNow
import ru.timegrip.app.ui.main.LocalSnackbarHostState
import ru.timegrip.app.ui.theme.LocalExtraColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimersScreen() {
    val vm = appViewModel { TimersViewModel(it.projectRepository, it.timerRepository, it.syncManager) }
    val state by vm.state.collectAsStateWithLifecycle()
    val user by LocalAppContainer.current.sessionStore.state.collectAsStateWithLifecycle()
    val timeFormat = user.user?.timeFormat ?: TimeFormat.H24
    val snackbar = LocalSnackbarHostState.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val createProjectFirst = stringResource(R.string.create_project_first)
    var confirmingBulkDelete by remember { mutableStateOf(false) }
    val selecting = vm.selection.isNotEmpty()

    BackHandler(enabled = selecting) { vm.clearSelection() }

    val listState = rememberLazyListState()

    Scaffold(
        floatingActionButton = {
            if (!selecting) {
                FabOnList(listState) {
                    FloatingActionButton(
                        onClick = {
                            if (state.activeProjects.isEmpty()) {
                                scope.launch { snackbar.showSnackbar(createProjectFirst) }
                            } else {
                                vm.openNew()
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        // Material 3 now shapes a FAB as a rounded square; ours is a circle.
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_time_entry))
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // The filter row stays put while the list scrolls under it, in the same spot
            // as on the other screens, so swiping between them does not move it.
            Column(Modifier.fillMaxSize()) {
                // Selecting replaces the filters with its own actions in the same spot,
                // instead of adding a bar above them that would shift the list down.
                if (selecting) {
                    SelectionBar(
                        count = vm.selection.size,
                        onClear = vm::clearSelection,
                        onSelectAll = vm::selectAll,
                        onDelete = { confirmingBulkDelete = true },
                    )
                } else {
                    FilterBar {
                        DateRangeFilterChip(state.filters.range, vm::setRange)
                        ProjectFilterChip(
                            projects = state.filterOptions,
                            selectedId = state.filters.projectId,
                            onChange = vm::setProject,
                            showArchived = state.filters.showArchived,
                            onShowArchivedChange = vm::setShowArchived,
                        )
                        BillingFilterChip(state.filters.billing, vm::setBilling)
                    }
                }
                LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                    vm.bulkError?.let { error ->
                        item { ErrorBanner(error, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    }
                    if (state.loaded && state.days.isEmpty()) {
                        item { MessageCard(stringResource(R.string.no_time_entries), Modifier.padding(horizontal = 16.dp)) }
                    }
                    state.days.forEach { day ->
                        stickyHeader(key = "day-${day.date}") { DayHeader(day.date, day.totalSeconds) }
                        items(day.entries, key = { it.id }) { entry ->
                            EntryRow(
                                entry = entry,
                                project = state.projectById[entry.projectId],
                                timeFormat = timeFormat,
                                selecting = selecting,
                                selected = entry.id in vm.selection,
                                onClick = { if (selecting) vm.toggleSelection(entry) else vm.openEdit(entry) },
                                onLongClick = { vm.toggleSelection(entry) },
                            )
                            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    vm.editor?.let { editor ->
        TimerEditorDialog(editor, state.projects, timeFormat, vm)
    }

    if (confirmingBulkDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_time_entries),
            message = pluralStringResource(R.plurals.delete_time_entries_message, vm.selection.size, vm.selection.size),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                confirmingBulkDelete = false
                vm.deleteSelected()
            },
            onDismiss = { confirmingBulkDelete = false },
        )
    }
}

/**
 * Stands in for [FilterBar] while selecting, in the same spot. It shares [FilterBarHeight] and
 * [FilterBarBottomGap] with every filter row in the app, so swapping one for the other never
 * shifts the list underneath.
 */
@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = FilterBarBottomGap)
            .heightIn(min = FilterBarHeight),
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
            Text(
                pluralStringResource(R.plurals.n_selected, count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.select_all))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_selected))
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, totalSeconds: Long) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                date.format(DateTimeFormatter.ofPattern("EEE, ")) + formatDate(date),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(formatClock(totalSeconds), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: TimeEntry,
    project: Project?,
    timeFormat: TimeFormat,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val running = entry.isRunning
    val now = rememberNow(enabled = running)
    val secondary = if (running) LocalExtraColors.current.running else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = !running, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = !running)
            Spacer(Modifier.width(8.dp))
        }
        ColorDot(project?.color ?: "#9E9E9E", size = 12.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project?.name ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                SyncMarkIcon(entry.syncMark)
            }
            Text(
                buildString {
                    append(formatTime(entry.start, timeFormat))
                    append(" – ")
                    val end = entry.end
                    when {
                        end == null -> append(stringResource(R.string.running))
                        end.toLocalDate() == entry.start.toLocalDate() -> append(formatTime(end, timeFormat))
                        else -> append(formatDateTime(end, timeFormat))
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatClock(entry.durationSeconds(now)),
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = if (running) FontWeight.Bold else FontWeight.Normal,
                color = if (running) secondary else MaterialTheme.colorScheme.onSurface,
            )
            entry.billableAmount?.let {
                Text(formatAmount(it), style = MaterialTheme.typography.bodySmall, color = secondary)
            }
        }
    }
}

/** Web: components/TimerFormModal.tsx. */
@Composable
private fun TimerEditorDialog(editor: TimerEditor, projects: List<Project>, timeFormat: TimeFormat, vm: TimersViewModel) {
    val existing = (editor as? TimerEditor.Existing)?.entry
    var projectId by rememberSaveable { mutableStateOf(existing?.projectId) }
    var startDate by rememberSaveable { mutableStateOf(existing?.start?.toLocalDate()) }
    var startTime by rememberSaveable { mutableStateOf(existing?.start?.atZone(zone())?.toLocalTime()?.withSecond(0)?.withNano(0)) }
    var endDate by rememberSaveable { mutableStateOf(existing?.end?.toLocalDate()) }
    var endTime by rememberSaveable { mutableStateOf(existing?.end?.atZone(zone())?.toLocalTime()?.withSecond(0)?.withNano(0)) }
    var showValidation by rememberSaveable { mutableStateOf(false) }
    var pickingProject by remember { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    // Archived projects can't take new entries, except the one the entry already belongs to.
    val selectable = projects.filter { it.isActive || it.id == existing?.projectId }
    val project = projects.firstOrNull { it.id == projectId }
    val start = startDate?.let { d -> startTime?.let { d.atTime(it) } }?.atZone(zone())?.toInstant()
    val end = endDate?.let { d -> endTime?.let { d.atTime(it) } }?.atZone(zone())?.toInstant()
    val today = LocalDate.now()

    FullScreenDialog(
        title = stringResource(if (existing == null) R.string.add_time_entry else R.string.edit_time_entry),
        actionLabel = stringResource(if (existing == null) R.string.add else R.string.save),
        actionEnabled = !vm.saving,
        onDismiss = vm::closeEditor,
        onAction = {
            if (!vm.save(TimerForm(projectId, startDate, startTime, endDate, endTime))) showValidation = true
        },
        onDelete = existing?.let { { confirmingDelete = true } },
        deleteEnabled = !vm.saving,
    ) {
        Column {
            SectionLabel(stringResource(R.string.project))
            Spacer(Modifier.size(8.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (showValidation && projectId == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { pickingProject = true },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (project != null) {
                        ColorDot(project.color)
                        Spacer(Modifier.width(12.dp))
                        Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(stringResource(R.string.select_project), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (showValidation && projectId == null) {
                Text(stringResource(R.string.project_required), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        DateTimeInputs(
            label = stringResource(R.string.start_time),
            date = startDate,
            time = startTime,
            onDate = { startDate = it },
            onTime = {
                startTime = it
                if (startDate == null) startDate = today
            },
            timeFormat = timeFormat,
            maxDate = today,
            minDate = null,
            missing = showValidation && start == null,
            missingText = stringResource(R.string.start_time_required),
        )
        DateTimeInputs(
            label = stringResource(R.string.end_time),
            date = endDate,
            time = endTime,
            onDate = { endDate = it },
            onTime = {
                endTime = it
                if (endDate == null) endDate = startDate ?: today
            },
            timeFormat = timeFormat,
            maxDate = today,
            minDate = startDate,
            missing = showValidation && end == null,
            missingText = stringResource(R.string.end_time_required),
        )
        if (start != null && end != null && !end.isBefore(start)) {
            Text(
                stringResource(
                    R.string.total_duration,
                    formatDurationBetween(start, end, stringResource(R.string.hour_short), stringResource(R.string.minute_short)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ErrorBanner(vm.editorError)
    }

    if (pickingProject) {
        ProjectPickerWindow(
            projects = selectable,
            title = stringResource(R.string.select_project),
            selectedId = projectId,
            onSelect = { projectId = it },
            onDismiss = { pickingProject = false },
        )
    }

    if (confirmingDelete && existing != null) {
        ConfirmDialog(
            title = stringResource(R.string.delete_time_entry),
            message = stringResource(R.string.delete_time_entry_message),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                confirmingDelete = false
                vm.delete(existing)
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
private fun DateTimeInputs(
    label: String,
    date: LocalDate?,
    time: LocalTime?,
    onDate: (LocalDate) -> Unit,
    onTime: (LocalTime) -> Unit,
    timeFormat: TimeFormat,
    maxDate: LocalDate,
    minDate: LocalDate?,
    missing: Boolean,
    missingText: String,
) {
    Column {
        SectionLabel(label)
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField(
                value = date,
                onChange = onDate,
                label = stringResource(R.string.date),
                minDate = minDate,
                maxDate = maxDate,
                isError = missing && date == null,
                modifier = Modifier.weight(1.2f),
            )
            TimeField(
                value = time,
                onChange = onTime,
                label = stringResource(R.string.time),
                timeFormat = timeFormat,
                isError = missing && time == null,
                modifier = Modifier.weight(1f),
            )
        }
        if (missing) {
            Text(missingText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
