package ru.timegrip.app.ui.projects

import ru.timegrip.app.ui.common.PanelCard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.timegrip.app.R
import ru.timegrip.app.domain.Project
import ru.timegrip.app.domain.ProjectColors
import ru.timegrip.app.domain.ProjectStatus
import ru.timegrip.app.domain.StatusFilter
import ru.timegrip.app.domain.formatAmount
import ru.timegrip.app.ui.common.Banner
import ru.timegrip.app.ui.common.BillingFilterChip
import ru.timegrip.app.ui.common.ColorDot
import ru.timegrip.app.ui.common.ConfirmDialog
import ru.timegrip.app.ui.common.ChoiceFilterChip
import ru.timegrip.app.ui.common.ErrorBanner
import ru.timegrip.app.ui.common.FabOnList
import ru.timegrip.app.ui.common.FilterBar
import ru.timegrip.app.ui.common.FullScreenDialog
import ru.timegrip.app.ui.common.MessageCard
import ru.timegrip.app.ui.common.SearchBar
import ru.timegrip.app.ui.common.SectionLabel
import ru.timegrip.app.ui.common.SyncMarkIcon
import ru.timegrip.app.ui.common.appViewModel
import ru.timegrip.app.ui.common.parseColor
import ru.timegrip.app.ui.common.requiredError
import ru.timegrip.app.ui.main.LocalSnackbarHostState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen() {
    val vm = appViewModel { ProjectsViewModel(it.projectRepository, it.timerRepository, it.syncManager) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val noticeTemplate = stringResource(R.string.filter_match_notice)
    LaunchedEffect(vm) {
        vm.notices.collect { name -> snackbar.showSnackbar(noticeTemplate.format(name)) }
    }

    val listState = rememberLazyListState()

    Scaffold(
        floatingActionButton = {
            FabOnList(listState) {
                FloatingActionButton(
                    onClick = vm::openNew,
                    // The filled primary colours of a Button: creating something looks the same
                    // whether the action sits on the screen or at the bottom of a window.
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    // Material 3 now shapes a FAB as a rounded square; ours is a circle.
                    shape = CircleShape,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_project))
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
                val search = state.search
                BackHandler(enabled = search != null, onBack = vm::closeSearch)
                if (search != null) {
                    SearchBar(search, vm::setSearch, onClose = vm::closeSearch)
                } else {
                    FilterBar(
                        trailing = {
                            IconButton(onClick = vm::openSearch) {
                                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search))
                            }
                        },
                    ) {
                        BillingFilterChip(state.filters.billing, vm::setBilling)
                        StatusFilterChip(state.filters.status, vm::setStatus)
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        !state.loaded -> Unit
                        state.all.isEmpty() -> item {
                            MessageCard(stringResource(R.string.no_projects_yet), Modifier.padding(horizontal = 16.dp))
                        }
                        state.visible.isEmpty() -> item {
                            MessageCard(stringResource(R.string.no_projects_match), Modifier.padding(horizontal = 16.dp))
                        }
                        else -> items(state.visible, key = { it.id }) { project ->
                            ProjectCard(project, showArchivedBadge = !search.isNullOrBlank() || state.filters.status == StatusFilter.ALL) {
                                vm.openEdit(project)
                            }
                        }
                    }
                }
            }
        }
    }

    vm.editor?.let { editor ->
        ProjectEditorDialog(
            editor = editor,
            vm = vm,
        )
    }
}

@Composable
private fun StatusFilterChip(value: StatusFilter, onChange: (StatusFilter) -> Unit) {
    val title = stringResource(R.string.project_status)
    val options = listOf(
        StatusFilter.ALL to stringResource(R.string.filter_all),
        StatusFilter.ACTIVE to stringResource(R.string.status_active),
        StatusFilter.ARCHIVED to stringResource(R.string.status_archived),
    )
    ChoiceFilterChip(
        title = title,
        options = options,
        selected = value,
        onSelect = onChange,
        active = value != StatusFilter.ACTIVE,
        label = if (value == StatusFilter.ALL) title else options.first { it.first == value }.second,
    )
}

@Composable
private fun ProjectCard(project: Project, showArchivedBadge: Boolean, onClick: () -> Unit) {
    val rate = project.hourlyRate
    PanelCard(Modifier.padding(horizontal = 16.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(project.color, size = 16.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (showArchivedBadge && project.status == ProjectStatus.ARCHIVED) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Text(
                                stringResource(R.string.archived_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    SyncMarkIcon(project.syncMark)
                }
                Text(
                    if (rate == null) {
                        stringResource(R.string.no_rate)
                    } else {
                        listOfNotNull(
                            stringResource(R.string.per_hour, formatAmount(rate)),
                            if (project.roundToHour) stringResource(R.string.rounded_to_hour_suffix) else null,
                        ).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Web: a "$" on the right marks a billable project.
            if (rate != null) {
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Outlined.AttachMoney, contentDescription = stringResource(R.string.billable))
            }
        }
    }
}

/** Web: components/ProjectFormModal.tsx. */
@Composable
private fun ProjectEditorDialog(editor: ProjectEditor, vm: ProjectsViewModel) {
    val existing = (editor as? ProjectEditor.Existing)?.project
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(existing?.color ?: ProjectColors.DEFAULT) }
    var rate by rememberSaveable { mutableStateOf(existing?.hourlyRate?.toPlainString().orEmpty()) }
    var roundToHour by rememberSaveable { mutableStateOf(existing?.roundToHour ?: false) }
    var status by rememberSaveable { mutableStateOf(existing?.status ?: ProjectStatus.ACTIVE) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val hasRate = rate.trim().replace(',', '.').toBigDecimalOrNull()?.signum()?.let { it != 0 } ?: false

    FullScreenDialog(
        title = stringResource(if (existing == null) R.string.new_project else R.string.edit_project),
        actionLabel = stringResource(if (existing == null) R.string.create else R.string.save),
        actionEnabled = !vm.saving,
        onDismiss = vm::closeEditor,
        onAction = {
            submitted = true
            if (name.isNotBlank()) vm.save(ProjectForm(name, color, rate, roundToHour, status))
        },
        onDelete = existing?.let { { if (vm.requestDelete(it)) confirmingDelete = true } },
        deleteEnabled = !vm.saving,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.project_name)) },
            singleLine = true,
            isError = requiredError(submitted, name) != null,
            supportingText = requiredError(submitted, name)?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Column {
            SectionLabel(stringResource(R.string.project_color))
            Spacer(Modifier.size(8.dp))
            ColorSwatchPicker(color) { color = it }
        }
        OutlinedTextField(
            value = rate,
            onValueChange = {
                rate = it
                if ((it.trim().replace(',', '.').toBigDecimalOrNull()?.signum() ?: 0) == 0) roundToHour = false
            },
            label = { Text(stringResource(R.string.hourly_rate)) },
            supportingText = { Text(stringResource(R.string.hourly_rate_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = hasRate) { roundToHour = !roundToHour },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = roundToHour, onCheckedChange = null, enabled = hasRate)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.round_to_hour_label),
                color = if (hasRate) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (existing != null) {
            Column {
                SectionLabel(stringResource(R.string.project_status))
                Spacer(Modifier.size(8.dp))
                val options = listOf(
                    ProjectStatus.ACTIVE to stringResource(R.string.project_status_active),
                    ProjectStatus.ARCHIVED to stringResource(R.string.project_status_archived),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = status == value,
                            onClick = { status = value },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(label) }
                    }
                }
            }
        }
        ErrorBanner(vm.editorError)
        if (vm.deleteBlocked) Banner(stringResource(R.string.delete_blocked_by_running_timer))
    }

    if (confirmingDelete && existing != null) {
        ConfirmDialog(
            title = stringResource(R.string.delete_project),
            message = stringResource(R.string.delete_project_message, existing.name),
            confirmLabel = stringResource(R.string.delete),
            confirming = vm.saving,
            onConfirm = {
                confirmingDelete = false
                vm.delete(existing)
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/** Web: components/ColorSwatchPicker.tsx. */
@Composable
private fun ColorSwatchPicker(value: String, onChange: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProjectColors.all.forEach { swatch ->
            val selected = swatch == value
            Surface(
                shape = CircleShape,
                color = parseColor(swatch),
                border = if (selected) {
                    androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
                } else {
                    null
                },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onChange(swatch) },
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = swatch,
                        tint = Color.White,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}
