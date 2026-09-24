package ru.timegrip.app.ui.common

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.timegrip.app.R
import ru.timegrip.app.domain.BillingFilter
import ru.timegrip.app.domain.DateRange
import ru.timegrip.app.domain.DateRangePreset
import ru.timegrip.app.domain.Project
import ru.timegrip.app.domain.ProjectStatus
import java.time.LocalDate
import java.time.ZoneId

/*
 * Every filter is a chip with a drop-down arrow that opens a full-screen
 * window: single-choice windows use radio buttons and close on selection,
 * multi-choice windows use checkboxes and apply what was picked on "Apply".
 */

/**
 * The height of a filter row's own content, and the gap below it before whatever follows.
 * A stand-in for [FilterBar] (Timers' selection bar) uses the same two values, so swapping
 * one for the other, or swiping between screens that each have their own filter row, never
 * shifts anything underneath.
 */
val FilterBarHeight = 48.dp
val FilterBarBottomGap = 4.dp

/**
 * The filter chips of a screen, always on one line of a fixed height: no wrapping,
 * no scrolling. When they do not fit, the shortest keep their full labels and the
 * rest share what is left equally, cutting the longest labels with an ellipsis.
 * [trailing] (e.g. a search button) stays at the end and never shrinks.
 */
@Composable
fun FilterBar(
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = FilterBarBottomGap)
            .height(FilterBarHeight)
            .padding(start = 16.dp, end = if (trailing == null) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Layout(content, Modifier.weight(1f)) { measurables, constraints ->
            val spacing = 8.dp.roundToPx()
            val available = (constraints.maxWidth - spacing * (measurables.size - 1)).coerceAtLeast(0)
            val wanted = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
            val cap = fairShare(wanted, available)
            val placeables = measurables.mapIndexed { i, measurable ->
                measurable.measure(Constraints(maxWidth = minOf(wanted[i], cap), maxHeight = constraints.maxHeight))
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                var x = 0
                placeables.forEach {
                    it.placeRelative(x, (constraints.maxHeight - it.height) / 2)
                    x += it.width + spacing
                }
            }
        }
        trailing?.invoke()
    }
}

/**
 * The widest any item may be so that all of them fit in [available]: items narrower
 * than an equal share of what is left keep their width, the others get that share.
 */
private fun fairShare(wanted: List<Int>, available: Int): Int {
    if (wanted.sum() <= available) return Int.MAX_VALUE
    var remaining = available
    wanted.sorted().forEachIndexed { index, width ->
        val share = remaining / (wanted.size - index)
        if (width > share) return share
        remaining -= width
    }
    return Int.MAX_VALUE
}

/** A filter chip; [active] highlights a value other than the default. */
@Composable
fun FilterChipButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    FilterChip(
        selected = active,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = leading,
        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
    )
}

/**
 * A full-screen window with a title, an optional action in the top bar, an
 * optional [header] above the list, and the list itself. `close` closes the
 * window. Every list of options in the app opens this way: the window has a
 * fixed size, so nothing resizes or shifts while it is open.
 */
@Composable
fun OptionsWindow(
    title: String,
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
    header: (@Composable () -> Unit)? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    content: LazyListScope.(close: () -> Unit) -> Unit,
) {
    FullScreenDialogFrame(
        title = title,
        actionLabel = actionLabel,
        onDismiss = onDismiss,
        onAction = onAction,
        actionEnabled = actionEnabled,
    ) { padding ->
        // With a confirm button the list gives it room at the bottom; without one
        // the list is the whole window and a choice closes it right away.
        val button: (@Composable () -> Unit)? = if (confirmLabel != null && onConfirm != null) {
            { ConfirmButton(confirmLabel, onClick = onConfirm) }
        } else {
            null
        }
        ConfirmButtonLayout(padding, button) {
            header?.invoke()
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) { content(onDismiss) }
        }
    }
}

/** A row of a window: optional color dot, label, and a radio button, checkbox or nothing on the right. */
@Composable
fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    control: OptionControl = OptionControl.RADIO,
    color: String? = null,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val interaction = when (control) {
        OptionControl.CHECKBOX -> Modifier.toggleable(selected, enabled, Role.Checkbox) { onClick() }
        OptionControl.RADIO -> Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        OptionControl.NONE -> Modifier.selectable(selected = selected, enabled = enabled, onClick = onClick)
    }
    ListItem(
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = color?.let { { ColorDot(it) } },
        trailingContent = when (control) {
            OptionControl.RADIO -> {
                { RadioButton(selected = selected, onClick = null, enabled = enabled) }
            }
            OptionControl.CHECKBOX -> {
                { Checkbox(checked = selected, onCheckedChange = null, enabled = enabled) }
            }
            OptionControl.NONE -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = interaction,
    )
}

enum class OptionControl { RADIO, CHECKBOX, NONE }

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
    )
}

/** A single-choice filter. */
@Composable
fun <T> ChoiceFilterChip(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    active: Boolean,
    label: String = options.first { it.first == selected }.second,
) {
    var open by remember { mutableStateOf(false) }
    FilterChipButton(label, active, onClick = { open = true })
    if (open) {
        OptionsWindow(title, onDismiss = { open = false }) { close ->
            items(options, key = { it.first.toString() }) { (value, text) ->
                OptionRow(text, selected = value == selected, onClick = {
                    onSelect(value)
                    close()
                })
            }
        }
    }
}

@Composable
fun BillingFilterChip(value: BillingFilter, onChange: (BillingFilter) -> Unit) {
    val title = stringResource(R.string.filter_billing)
    val options = listOf(
        BillingFilter.ALL to stringResource(R.string.filter_all),
        BillingFilter.BILLABLE to stringResource(R.string.billable),
        BillingFilter.NON_BILLABLE to stringResource(R.string.non_billable),
    )
    ChoiceFilterChip(
        title = title,
        options = options,
        selected = value,
        onSelect = onChange,
        active = value != BillingFilter.ALL,
        label = if (value == BillingFilter.ALL) title else options.first { it.first == value }.second,
    )
}

private const val SEARCH_THRESHOLD = 8

@Composable
private fun rememberProjectSearch(): Pair<String, (String) -> Unit> {
    var search by rememberSaveable { mutableStateOf("") }
    return search to { value: String -> search = value }
}

private fun List<Project>.matching(search: String) = filter { it.name.contains(search.trim(), ignoreCase = true) }

@Composable
private fun ProjectSearchHeader(projects: List<Project>, search: String, onSearch: (String) -> Unit) {
    if (projects.size > SEARCH_THRESHOLD) {
        SearchField(search, onSearch, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

private fun LazyListScope.noMatches(visible: List<Project>, search: String) {
    if (visible.isEmpty() && search.isNotBlank()) {
        item {
            Text(
                stringResource(R.string.no_matches),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Picks one project or all of them. With [onShowArchivedChange], the window
 * also has the "show archived projects" switch.
 */
@Composable
fun ProjectFilterChip(
    projects: List<Project>,
    selectedId: String?,
    onChange: (String?) -> Unit,
    showArchived: Boolean = false,
    onShowArchivedChange: ((Boolean) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val selected = projects.firstOrNull { it.id == selectedId }
    FilterChipButton(
        label = selected?.name ?: stringResource(R.string.filter_projects),
        active = selected != null || showArchived,
        onClick = { open = true },
        leading = selected?.let { { ColorDot(it.color, size = 10.dp) } },
    )
    if (open) {
        val (search, onSearch) = rememberProjectSearch()
        val visible = projects.matching(search)
        OptionsWindow(
            title = stringResource(R.string.filter_projects),
            onDismiss = { open = false },
            header = {
                if (onShowArchivedChange != null) {
                    SwitchRow(stringResource(R.string.show_archived_projects), showArchived, onShowArchivedChange)
                    HorizontalDivider()
                }
                ProjectSearchHeader(projects, search, onSearch)
            },
        ) { close ->
            if (search.isBlank()) {
                item {
                    OptionRow(stringResource(R.string.all_projects), selected = selectedId == null, onClick = {
                        onChange(null)
                        close()
                    })
                }
            }
            noMatches(visible, search)
            items(visible, key = { it.id }) { project ->
                OptionRow(project.name, selected = project.id == selectedId, color = project.color, onClick = {
                    onChange(project.id)
                    close()
                })
            }
        }
    }
}

/**
 * Inline project search that takes the place of a screen's [FilterBar]: same height
 * and gap, so the list below does not jump. Opens focused with the keyboard up;
 * the arrow closes it, the cross clears the query.
 */
@Composable
fun SearchBar(value: String, onValueChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = FilterBarBottomGap)
            .height(FilterBarHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close_search))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    stringResource(R.string.search),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
            }
        }
    }
}

/** Picks any number of projects; none means all. */
@Composable
fun ProjectsMultiFilterChip(projects: List<Project>, selectedIds: Set<String>, onChange: (Set<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = projects.filter { it.id in selectedIds }
    FilterChipButton(
        label = when (selected.size) {
            0 -> stringResource(R.string.filter_projects)
            1 -> selected.first().name
            else -> stringResource(R.string.projects_count, selected.size)
        },
        active = selected.isNotEmpty(),
        onClick = { open = true },
        leading = selected.singleOrNull()?.let { { ColorDot(it.color, size = 10.dp) } },
    )
    if (open) {
        val (search, onSearch) = rememberProjectSearch()
        val visible = projects.matching(search)
        // Several projects are picked before the screen behind has to redraw, so the picks
        // stay here until "Apply". The cross leaves the filter as it was.
        var picked by remember { mutableStateOf(selectedIds) }
        OptionsWindow(
            title = stringResource(R.string.filter_projects),
            onDismiss = { open = false },
            actionLabel = stringResource(R.string.reset),
            onAction = { picked = emptySet() },
            actionEnabled = picked.isNotEmpty(),
            header = { ProjectSearchHeader(projects, search, onSearch) },
            confirmLabel = stringResource(R.string.apply),
            onConfirm = {
                onChange(picked)
                open = false
            },
        ) {
            noMatches(visible, search)
            items(visible, key = { it.id }) { project ->
                val checked = project.id in picked
                OptionRow(
                    project.name,
                    selected = checked,
                    control = OptionControl.CHECKBOX,
                    color = project.color,
                    onClick = { picked = if (checked) picked - project.id else picked + project.id },
                )
            }
        }
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(stringResource(R.string.search_projects)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        // Like Telegram: a cross clears the query once there is one.
        trailingIcon = if (value.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                }
            }
        },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Picks a project to act on (start a timer, assign an entry). With
 * [selectedId], the rows show which one is chosen.
 */
@Composable
fun ProjectPickerWindow(
    projects: List<Project>,
    title: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    selectedId: String? = null,
    enabled: Boolean = true,
) {
    val (search, onSearch) = rememberProjectSearch()
    val visible = projects.matching(search)
    OptionsWindow(title, onDismiss, header = { ProjectSearchHeader(projects, search, onSearch) }) { close ->
        if (projects.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_projects_yet_short),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        noMatches(visible, search)
        items(visible, key = { it.id }) { project ->
            OptionRow(
                project.name,
                selected = project.id == selectedId,
                control = if (selectedId == null) OptionControl.NONE else OptionControl.RADIO,
                color = project.color,
                enabled = enabled,
                onClick = {
                    onSelect(project.id)
                    close()
                },
            )
        }
    }
}

@Composable
fun presetLabel(preset: DateRangePreset): String = stringResource(
    when (preset) {
        DateRangePreset.TODAY -> R.string.preset_today
        DateRangePreset.YESTERDAY -> R.string.preset_yesterday
        DateRangePreset.THIS_WEEK -> R.string.preset_this_week
        DateRangePreset.LAST_WEEK -> R.string.preset_last_week
        DateRangePreset.THIS_MONTH -> R.string.preset_this_month
        DateRangePreset.LAST_MONTH -> R.string.preset_last_month
        DateRangePreset.THIS_YEAR -> R.string.preset_this_year
        DateRangePreset.LAST_YEAR -> R.string.preset_last_year
    },
)

/**
 * A compact, localized date or range: "21 Sep", "10–25 Sep", "28 Sep – 5 Oct";
 * the year appears only outside the current one.
 */
private fun formatShortRange(context: Context, from: LocalDate, to: LocalDate = from): String {
    fun LocalDate.millis() = atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val thisYear = LocalDate.now().year
    val year = if (from.year == thisYear && to.year == thisYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    return DateUtils.formatDateRange(
        context,
        from.millis(),
        to.millis(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or year,
    )
}

@Composable
fun dateRangeLabel(range: DateRange): String {
    if (range.isAll) return stringResource(R.string.all_dates)
    DateRangePreset.matching(range)?.let { return presetLabel(it) }
    val from = range.from ?: range.to ?: return stringResource(R.string.all_dates)
    return formatShortRange(LocalContext.current, from, range.to ?: from)
}

/** The period filter: presets, "all dates" and a custom range. */
@Composable
fun DateRangeFilterChip(
    range: DateRange,
    onChange: (DateRange) -> Unit,
    default: DateRange = DateRange.thisMonth(),
) {
    var sheetOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    FilterChipButton(
        label = dateRangeLabel(range),
        active = range != default,
        onClick = { sheetOpen = true },
        leading = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
    )
    if (sheetOpen) {
        val preset = DateRangePreset.matching(range)
        val custom = preset == null && !range.isAll
        OptionsWindow(stringResource(R.string.period), onDismiss = { sheetOpen = false }) { close ->
            items(DateRangePreset.entries) { item ->
                OptionRow(presetLabel(item), selected = item == preset, onClick = {
                    onChange(item.range())
                    close()
                })
            }
            item {
                OptionRow(stringResource(R.string.all_dates), selected = range.isAll, onClick = {
                    onChange(DateRange.ALL)
                    close()
                })
            }
            item {
                // Not one of the choices above: this row opens the calendar, so it carries no
                // radio button of its own. The dates it picks show up as its supporting text.
                OptionRow(
                    stringResource(R.string.custom_range),
                    selected = custom,
                    control = OptionControl.NONE,
                    supporting = if (custom) dateRangeLabel(range) else null,
                    onClick = {
                        close()
                        customOpen = true
                    },
                )
            }
        }
    }
    if (customOpen) {
        DateRangeDialog(range, onDismiss = { customOpen = false }) {
            customOpen = false
            onChange(it)
        }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L

private fun Long.toLocalDate(): LocalDate = LocalDate.ofEpochDay(this / MILLIS_PER_DAY)

/** Material's full-screen date range picker: on a phone it doesn't fit in a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(range: DateRange, onDismiss: () -> Unit, onConfirm: (DateRange) -> Unit) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = range.from?.toEpochDay()?.times(MILLIS_PER_DAY),
        initialSelectedEndDateMillis = range.to?.toEpochDay()?.times(MILLIS_PER_DAY),
    )
    FullScreenDialogFrame(
        title = "",
        actionLabel = null,
        onDismiss = onDismiss,
        onAction = null,
    ) { padding ->
        ConfirmButtonLayout(
            padding,
            button = {
                ConfirmButton(
                    stringResource(R.string.apply),
                    enabled = state.selectedStartDateMillis != null,
                ) {
                    val start = state.selectedStartDateMillis?.toLocalDate()
                    val end = state.selectedEndDateMillis?.toLocalDate() ?: start
                    if (start != null) onConfirm(DateRange(start, end))
                }
            },
        ) {
            DateRangePicker(
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                headline = { RangeHeadline(state) },
                colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                showModeToggle = true,
            )
        }
    }
}

/** One line, shrinking if needed; the stock headline wraps the end date in some locales. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeHeadline(state: DateRangePickerState) {
    val start = state.selectedStartDateMillis?.toLocalDate()
    val end = state.selectedEndDateMillis?.toLocalDate()
    val context = LocalContext.current
    val text = when {
        start == null -> stringResource(R.string.date_range_open_start) + " – " + stringResource(R.string.date_range_open_end)
        end == null -> formatShortRange(context, start) + " – " + stringResource(R.string.date_range_open_end)
        else -> formatShortRange(context, start, end)
    }
    Text(
        text,
        maxLines = 1,
        style = MaterialTheme.typography.headlineLarge,
        autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = MaterialTheme.typography.headlineLarge.fontSize),
        modifier = Modifier.padding(start = 64.dp, end = 12.dp, bottom = 12.dp),
    )
}
