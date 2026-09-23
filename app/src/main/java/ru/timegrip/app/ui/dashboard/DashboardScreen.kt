package ru.timegrip.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.timegrip.app.R
import ru.timegrip.app.data.repository.ProjectRepository
import ru.timegrip.app.data.repository.TimerRepository
import ru.timegrip.app.data.sync.SyncManager
import ru.timegrip.app.domain.BillingFilter
import ru.timegrip.app.domain.DateRange
import ru.timegrip.app.domain.OTHER_SLICE_KEY
import ru.timegrip.app.domain.Project
import ru.timegrip.app.domain.ProjectSlice
import ru.timegrip.app.domain.TimeGranularity
import ru.timegrip.app.domain.TimeSeries
import ru.timegrip.app.domain.aggregateProjectTotals
import ru.timegrip.app.domain.aggregateTimeSeries
import ru.timegrip.app.domain.foldIntoChartSlices
import ru.timegrip.app.domain.formatAmount
import ru.timegrip.app.domain.formatClock
import ru.timegrip.app.domain.sumBillableAmount
import ru.timegrip.app.domain.sumDurationSeconds
import ru.timegrip.app.domain.topByAmount
import ru.timegrip.app.ui.common.BarChart
import ru.timegrip.app.ui.common.BillingFilterChip
import ru.timegrip.app.ui.common.ColorDot
import ru.timegrip.app.ui.common.DateRangeFilterChip
import ru.timegrip.app.ui.common.DonutChart
import ru.timegrip.app.ui.common.DonutSlice
import ru.timegrip.app.ui.common.FilterBar
import ru.timegrip.app.ui.common.MessageCard
import ru.timegrip.app.ui.common.PanelCard
import ru.timegrip.app.ui.common.ProjectsMultiFilterChip
import ru.timegrip.app.ui.common.appViewModel
import ru.timegrip.app.ui.common.parseColor
import java.math.BigDecimal

data class DashboardFilters(
    val projectIds: Set<String> = emptySet(),
    val billing: BillingFilter = BillingFilter.ALL,
    val range: DateRange = DateRange.thisMonth(),
)

data class DashboardState(
    val filters: DashboardFilters = DashboardFilters(),
    val projects: List<Project> = emptyList(),
    val totalSeconds: Long = 0,
    val totalAmount: BigDecimal = BigDecimal.ZERO,
    val totals: List<ProjectSlice> = emptyList(),
    val slices: List<ProjectSlice> = emptyList(),
    val series: TimeSeries = TimeSeries(TimeGranularity.DAY, emptyList()),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    projectRepository: ProjectRepository,
    timerRepository: TimerRepository,
    private val syncManager: SyncManager,
) : ViewModel() {
    private val filters = MutableStateFlow(DashboardFilters())
    private val projects = projectRepository.observeProjects()

    // Works entirely on the local copy, so the dashboard is available offline.
    val state: StateFlow<DashboardState> = filters.flatMapLatest { f ->
        combine(timerRepository.observeEntries(f.range), projects) { entries, projects ->
            val selected = entries.filter { entry ->
                !entry.isRunning &&
                    (f.projectIds.isEmpty() || entry.projectId in f.projectIds) &&
                    when (f.billing) {
                        BillingFilter.ALL -> true
                        BillingFilter.BILLABLE -> entry.hourlyRate != null
                        BillingFilter.NON_BILLABLE -> entry.hourlyRate == null
                    }
            }
            val totals = aggregateProjectTotals(selected, projects.associateBy { it.id }, unknownProjectLabel = "")
            DashboardState(
                filters = f,
                projects = projects,
                totalSeconds = sumDurationSeconds(selected),
                totalAmount = sumBillableAmount(selected),
                totals = totals,
                slices = foldIntoChartSlices(totals, otherLabel = ""),
                series = aggregateTimeSeries(selected, f.range),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    var refreshing by mutableStateOf(false)
        private set

    init {
        syncManager.requestRangeRefresh(filters.value.range)
    }

    fun setProjects(ids: Set<String>) = filters.update { it.copy(projectIds = ids) }

    /** Changing billing clears the project selection, as on the web. */
    fun setBilling(billing: BillingFilter) = filters.update { it.copy(billing = billing, projectIds = emptySet()) }

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val vm = appViewModel { DashboardViewModel(it.projectRepository, it.timerRepository, it.syncManager) }
    val state by vm.state.collectAsStateWithLifecycle()
    val f = state.filters
    val billingProjects = when (f.billing) {
        BillingFilter.ALL -> state.projects
        BillingFilter.BILLABLE -> state.projects.filter { it.isBillable }
        BillingFilter.NON_BILLABLE -> state.projects.filter { !it.isBillable }
    }
    val unknown = stringResource(R.string.unknown_project)
    val other = stringResource(R.string.other_projects)
    fun ProjectSlice.title() = when {
        key == OTHER_SLICE_KEY -> other
        label.isEmpty() -> unknown
        else -> label
    }

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
            // The filter row stays put while the list scrolls under it, in the same spot
            // as on the other screens, so swiping between them does not move it.
            Column(Modifier.fillMaxSize()) {
                FilterBar {
                    DateRangeFilterChip(f.range, vm::setRange)
                    ProjectsMultiFilterChip(billingProjects, f.projectIds, vm::setProjects)
                    BillingFilterChip(f.billing, vm::setBilling)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        val showAmount = f.billing != BillingFilter.NON_BILLABLE
                        val topByTime = state.totals.firstOrNull()
                        val topAmount = if (showAmount) topByAmount(state.totals) else null
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatCard(stringResource(R.string.total_time), formatClock(state.totalSeconds), Modifier.weight(1f))
                                StatCard(stringResource(R.string.total_amount), formatAmount(state.totalAmount), Modifier.weight(1f))
                            }
                            PanelCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                                Column {
                                    TopProjectRow(
                                        stringResource(R.string.top_project_by_time),
                                        topByTime,
                                        topByTime?.title(),
                                        topByTime?.let { formatClock(it.totalSeconds) },
                                    )
                                    if (showAmount) {
                                        TopProjectRow(
                                            stringResource(R.string.top_project_by_amount),
                                            topAmount,
                                            topAmount?.title(),
                                            topAmount?.let { formatAmount(it.totalAmount) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        PanelCard(Modifier.padding(horizontal = 16.dp)) {
                            Column {
                                Text(stringResource(R.string.time_by_project), style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(16.dp))
                                if (state.slices.isEmpty()) {
                                    Text(
                                        stringResource(R.string.no_time_entries),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    DonutChart(
                                        slices = state.slices.map { DonutSlice(it.key, it.title(), it.totalSeconds, parseColor(it.color)) },
                                        formatValue = ::formatClock,
                                        centerLabel = formatClock(state.totalSeconds),
                                        modifier = Modifier
                                            .size(200.dp)
                                            .align(Alignment.CenterHorizontally),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    state.slices.forEach { slice ->
                                        LegendRow(slice, slice.title(), state.totalSeconds)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        PanelCard(Modifier.padding(horizontal = 16.dp)) {
                            Column {
                                Text(
                                    stringResource(
                                        when (state.series.granularity) {
                                            TimeGranularity.HOUR -> R.string.time_by_hour
                                            TimeGranularity.DAY -> R.string.time_by_day
                                            TimeGranularity.MONTH -> R.string.time_by_month
                                        },
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Spacer(Modifier.height(12.dp))
                                if (state.series.points.isEmpty()) {
                                    MessageCard(stringResource(R.string.no_time_entries))
                                } else {
                                    BarChart(state.series.points, ::formatClock)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    PanelCard(modifier) {
        Column {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = MaterialTheme.typography.headlineSmall.fontSize),
            )
        }
    }
}

/** "Top project by time: ● Name ... 14:00" on one full-width row. */
@Composable
private fun TopProjectRow(title: String, slice: ProjectSlice?, name: String?, value: String?) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (slice != null) {
                ColorDot(slice.color)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                name ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            value?.let {
                Spacer(Modifier.width(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LegendRow(slice: ProjectSlice, title: String, totalSeconds: Long) {
    val secondary = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.End,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(slice.color)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(formatClock(slice.totalSeconds), style = secondary, maxLines = 1, modifier = Modifier.padding(start = 8.dp).widthIn(min = 48.dp))
        Text(
            "${if (totalSeconds > 0) Math.round(slice.totalSeconds * 100.0 / totalSeconds) else 0}%",
            style = secondary,
            maxLines = 1,
            modifier = Modifier.width(48.dp),
        )
    }
}
