package ru.timegrip.app.ui.report

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.timegrip.app.R
import ru.timegrip.app.data.repository.ProjectRepository
import ru.timegrip.app.data.repository.TimerRepository
import ru.timegrip.app.data.sync.SyncManager
import ru.timegrip.app.domain.BillingFilter
import ru.timegrip.app.domain.DEFAULT_REPORT_COLUMNS
import ru.timegrip.app.domain.DateRange
import ru.timegrip.app.domain.GroupBy
import ru.timegrip.app.domain.Project
import ru.timegrip.app.domain.ReportColumn
import ru.timegrip.app.domain.ReportCsv
import ru.timegrip.app.domain.ReportGroup
import ru.timegrip.app.domain.TimeEntry
import ru.timegrip.app.domain.TimeFormat
import ru.timegrip.app.domain.formatAmount
import ru.timegrip.app.domain.formatClock
import ru.timegrip.app.domain.formatDate
import ru.timegrip.app.domain.formatTime
import ru.timegrip.app.domain.groupEntries
import ru.timegrip.app.domain.groupedColumn
import ru.timegrip.app.domain.sumBillableAmount
import ru.timegrip.app.domain.sumDurationSeconds
import ru.timegrip.app.ui.common.BillingFilterChip
import ru.timegrip.app.ui.common.ColorDot
import ru.timegrip.app.ui.common.DateRangeFilterChip
import ru.timegrip.app.ui.common.FilterBar
import ru.timegrip.app.ui.common.LocalAppContainer
import ru.timegrip.app.ui.common.MessageCard
import ru.timegrip.app.ui.common.OptionControl
import ru.timegrip.app.ui.common.OptionRow
import ru.timegrip.app.ui.common.OptionsWindow
import ru.timegrip.app.ui.common.ProjectsMultiFilterChip
import ru.timegrip.app.ui.common.SectionLabel
import ru.timegrip.app.ui.common.appViewModel
import ru.timegrip.app.ui.main.LocalSnackbarHostState
import java.math.BigDecimal
import java.time.LocalDate

data class ReportFilters(
    val projectIds: Set<String> = emptySet(),
    val billing: BillingFilter = BillingFilter.ALL,
    val range: DateRange = DateRange.thisMonth(),
)

data class ReportResult(
    val filters: ReportFilters,
    val entries: List<TimeEntry>,
    val projectById: Map<String, Project>,
)

/** The report is built from the local copy, so it works offline. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModel(
    projectRepository: ProjectRepository,
    private val timerRepository: TimerRepository,
    private val syncManager: SyncManager,
) : ViewModel() {
    val filters = MutableStateFlow(ReportFilters())
    val projects: StateFlow<List<Project>> = projectRepository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var groupBy by mutableStateOf(GroupBy.NONE)
    var columns by mutableStateOf(DEFAULT_REPORT_COLUMNS)
    var refreshing by mutableStateOf(false)
        private set

    val result: StateFlow<ReportResult?> = filters.flatMapLatest { f ->
        combine(timerRepository.observeEntries(f.range), projects) { entries, projects ->
            ReportResult(
                filters = f,
                entries = entries.filter { entry ->
                    !entry.isRunning &&
                        (f.projectIds.isEmpty() || entry.projectId in f.projectIds) &&
                        when (f.billing) {
                            BillingFilter.ALL -> true
                            BillingFilter.BILLABLE -> entry.hourlyRate != null
                            BillingFilter.NON_BILLABLE -> entry.hourlyRate == null
                        }
                },
                projectById = projects.associateBy { it.id },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        syncManager.requestRangeRefresh(filters.value.range)
    }

    fun setProjects(ids: Set<String>) = filters.update { it.copy(projectIds = ids) }
    fun setBilling(value: BillingFilter) = filters.update { it.copy(billing = value) }

    fun setRange(range: DateRange) {
        filters.update { it.copy(range = range) }
        syncManager.requestRangeRefresh(range)
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            syncManager.syncNow(filters.value.range)
            refreshing = false
        }
    }

    fun export(context: Context, uri: Uri, csv: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                        ?: error("no stream")
                }.isSuccess
            }
            onDone(ok)
        }
    }
}

@Composable
private fun columnLabel(column: ReportColumn): String = stringResource(
    when (column) {
        ReportColumn.DATE -> R.string.col_date
        ReportColumn.PROJECT -> R.string.col_project
        ReportColumn.START -> R.string.col_start
        ReportColumn.END -> R.string.col_end
        ReportColumn.DURATION -> R.string.col_duration
        ReportColumn.ROUNDED -> R.string.col_rounded
        ReportColumn.AMOUNT -> R.string.col_amount
    },
)

/**
 * Web: pages/ReportPage.tsx. On a phone the table becomes a list: the chosen
 * fields fill each row, and the CSV export keeps them as columns.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReportScreen() {
    val vm = appViewModel { ReportViewModel(it.projectRepository, it.timerRepository, it.syncManager) }
    val filters by vm.filters.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val session by LocalAppContainer.current.sessionStore.state.collectAsStateWithLifecycle()
    val timeFormat = session.user?.timeFormat ?: TimeFormat.H24
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    val unknownProject = stringResource(R.string.unknown_project)
    val current = result
    val groups = remember(current, vm.groupBy, unknownProject) {
        current?.let { groupEntries(it.entries, vm.groupBy, it.projectById, unknownProject) }.orEmpty()
    }
    val hidden = vm.groupBy.groupedColumn()
    val activeColumns = ReportColumn.entries.filter { it in vm.columns && it != hidden }
    val canExport = current != null && current.entries.isNotEmpty() && activeColumns.isNotEmpty()

    val labels = ReportCsv.Labels(
        columns = ReportColumn.entries.associateWith { columnLabel(it) },
        total = stringResource(R.string.total),
        entries = stringResource(R.string.report_entries_word),
    )
    val savedText = stringResource(R.string.export_done)
    val failedText = stringResource(R.string.something_went_wrong)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val data = result ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val csv = ReportCsv.build(groups, activeColumns, vm.groupBy, data.projectById, timeFormat, labels)
        vm.export(context, uri, csv) { ok -> scope.launch { snackbar.showSnackbar(if (ok) savedText else failedText) } }
    }

    var viewOptionsOpen by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // The filters and totals stay put while the entries scroll under them. The filter
            // row sits exactly where the other screens have theirs, and the totals row is there
            // before the report loads, so neither swiping between screens nor loading moves them.
            Column(Modifier.fillMaxSize()) {
                FilterBar {
                    DateRangeFilterChip(filters.range, vm::setRange)
                    ProjectsMultiFilterChip(projects, filters.projectIds, vm::setProjects)
                    BillingFilterChip(filters.billing, vm::setBilling)
                }
                ReportHeader(
                    totalSeconds = current?.let { sumDurationSeconds(it.entries) },
                    totalAmount = current?.let { sumBillableAmount(it.entries) },
                    onViewOptions = { viewOptionsOpen = true },
                    onExport = if (canExport) ({ exporter.launch(exportFileName(filters.range)) }) else null,
                )
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    when {
                        current == null -> Unit
                        current.entries.isEmpty() -> item {
                            MessageCard(stringResource(R.string.no_time_entries), Modifier.padding(horizontal = 16.dp))
                        }
                        activeColumns.isEmpty() -> item {
                            MessageCard(stringResource(R.string.pick_column), Modifier.padding(horizontal = 16.dp))
                        }
                        else -> groups.forEach { group ->
                            if (vm.groupBy != GroupBy.NONE) {
                                stickyHeader(key = "group-${group.key}") {
                                    GroupHeader(group, color = current.projectById[group.key]?.color?.takeIf { vm.groupBy == GroupBy.PROJECT })
                                }
                            }
                            items(group.entries, key = { "${group.key}-${it.id}" }) { entry ->
                                EntryRow(entry, current.projectById[entry.projectId], activeColumns, timeFormat)
                                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (viewOptionsOpen) {
        ViewOptionsWindow(
            groupBy = vm.groupBy,
            onGroupBy = { vm.groupBy = it },
            columns = vm.columns,
            onColumns = { vm.columns = it },
            onDismiss = { viewOptionsOpen = false },
        )
    }
}

/** How the report is shown: grouping and the fields of each row (also the CSV columns). */
@Composable
private fun ViewOptionsWindow(
    groupBy: GroupBy,
    onGroupBy: (GroupBy) -> Unit,
    columns: Set<ReportColumn>,
    onColumns: (Set<ReportColumn>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Grouping and columns are picked together, so they reach the report on "Apply";
    // the cross leaves it as it was.
    var pickedGroupBy by remember { mutableStateOf(groupBy) }
    var pickedColumns by remember { mutableStateOf(columns) }
    val isDefault = pickedGroupBy == GroupBy.NONE && pickedColumns == DEFAULT_REPORT_COLUMNS
    OptionsWindow(
        title = stringResource(R.string.report_view),
        onDismiss = onDismiss,
        actionLabel = stringResource(R.string.reset),
        onAction = {
            pickedGroupBy = GroupBy.NONE
            pickedColumns = DEFAULT_REPORT_COLUMNS
        },
        actionEnabled = !isDefault,
        confirmLabel = stringResource(R.string.apply),
        onConfirm = {
            onGroupBy(pickedGroupBy)
            onColumns(pickedColumns)
            onDismiss()
        },
    ) {
        item { SheetSection(stringResource(R.string.grouping)) }
        items(
            listOf(
                GroupBy.NONE to R.string.no_grouping,
                GroupBy.PROJECT to R.string.group_by_project,
                GroupBy.DATE to R.string.group_by_date,
            ),
        ) { (value, label) ->
            OptionRow(stringResource(label), selected = value == pickedGroupBy, onClick = { pickedGroupBy = value })
        }
        item { SheetSection(stringResource(R.string.columns_title)) }
        // At least one field stays selected, as on the web.
        items(ReportColumn.entries) { column ->
            val checked = column in pickedColumns
            OptionRow(
                columnLabel(column),
                selected = checked,
                control = OptionControl.CHECKBOX,
                enabled = !checked || pickedColumns.size > 1,
                onClick = { pickedColumns = if (checked) pickedColumns - column else pickedColumns + column },
            )
        }
    }
}

@Composable
private fun SheetSection(text: String) {
    SectionLabel(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

private fun exportFileName(range: DateRange): String =
    if (range.from != null && range.to != null) "report_${range.from}_${range.to}.csv" else "report_${LocalDate.now()}.csv"

@Composable
private fun ReportHeader(
    totalSeconds: Long?,
    totalAmount: BigDecimal?,
    onViewOptions: () -> Unit,
    onExport: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.total), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(totalSeconds?.let(::formatClock).orEmpty(), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
                }
                Text(
                    totalAmount?.let(::formatAmount).orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.report_menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.report_view)) },
                        leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onViewOptions()
                        },
                    )
                    if (onExport != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_csv)) },
                            leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: ReportGroup, color: String?) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (color != null) {
                ColorDot(color, size = 10.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                group.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${formatClock(group.totalSeconds)} · ${formatAmount(group.totalAmount)}",
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One entry with the chosen fields: project (or date) on top, the rest below, totals on the right. */
@Composable
private fun EntryRow(entry: TimeEntry, project: Project?, columns: List<ReportColumn>, timeFormat: TimeFormat) {
    val date = if (ReportColumn.DATE in columns) formatDate(entry.start) else null
    val start = if (ReportColumn.START in columns) formatTime(entry.start, timeFormat) else null
    val end = if (ReportColumn.END in columns) ReportCsv.cellText(ReportColumn.END, entry, emptyMap(), timeFormat) else null
    val times = when {
        start != null && end != null -> "$start – $end"
        start != null -> start
        end != null -> "– $end"
        else -> null
    }
    val rounded = if (ReportColumn.ROUNDED in columns && entry.roundToHour) stringResource(R.string.rounded_short) else null
    val showProject = ReportColumn.PROJECT in columns
    val details = listOfNotNull(date, times, rounded)
    val headline = if (showProject) project?.name ?: "—" else details.firstOrNull()
    val supporting = (if (showProject) details else details.drop(1)).joinToString(" · ")
    val metrics = listOfNotNull(
        if (ReportColumn.DURATION in columns) formatClock(entry.durationSeconds()) else null,
        if (ReportColumn.AMOUNT in columns) entry.billableAmount?.let(::formatAmount) else null,
    )

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            headline?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (supporting.isNotEmpty()) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (metrics.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(metrics.first(), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
                metrics.getOrNull(1)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
