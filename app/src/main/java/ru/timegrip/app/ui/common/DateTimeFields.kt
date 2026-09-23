package ru.timegrip.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import ru.timegrip.app.R
import ru.timegrip.app.domain.TimeFormat
import ru.timegrip.app.domain.formatDate
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MILLIS_PER_DAY = 86_400_000L

fun formatLocalTime(time: LocalTime, timeFormat: TimeFormat): String = time.format(
    when (timeFormat) {
        TimeFormat.H24 -> DateTimeFormatter.ofPattern("HH:mm")
        TimeFormat.H12 -> DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())
    },
)

/** A read-only text field that opens a picker when tapped. */
@Composable
private fun PickerField(
    value: String,
    label: String,
    icon: ImageVector,
    isError: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            isError = isError,
            label = { Text(label) },
            trailingIcon = { Icon(icon, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable(onClick = onClick),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: LocalDate?,
    onChange: (LocalDate) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null,
    isError: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    PickerField(value?.let(::formatDate).orEmpty(), label, Icons.Outlined.CalendarMonth, isError, modifier) { open = true }
    if (!open) return

    val state = rememberDatePickerState(
        initialSelectedDateMillis = value?.toEpochDay()?.times(MILLIS_PER_DAY),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val day = LocalDate.ofEpochDay(utcTimeMillis / MILLIS_PER_DAY)
                return (minDate == null || !day.isBefore(minDate)) && (maxDate == null || !day.isAfter(maxDate))
            }

            override fun isSelectableYear(year: Int): Boolean =
                (minDate == null || year >= minDate.year) && (maxDate == null || year <= maxDate.year)
        },
    )
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onChange(LocalDate.ofEpochDay(it / MILLIS_PER_DAY)) }
                    open = false
                },
            ) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    value: LocalTime?,
    onChange: (LocalTime) -> Unit,
    label: String,
    timeFormat: TimeFormat,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    PickerField(
        value?.let { formatLocalTime(it, timeFormat) }.orEmpty(),
        label,
        Icons.Outlined.Schedule,
        isError,
        modifier,
    ) { open = true }
    if (!open) return

    val initial = value ?: LocalTime.now()
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = timeFormat == TimeFormat.H24,
    )
    AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(onClick = {
                onChange(LocalTime.of(state.hour, state.minute))
                open = false
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        text = { TimePicker(state = state) },
    )
}
