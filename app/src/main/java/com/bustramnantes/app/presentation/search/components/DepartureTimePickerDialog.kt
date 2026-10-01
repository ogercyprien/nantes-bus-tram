package com.bustramnantes.app.presentation.search.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bustramnantes.app.domain.util.lastSchedulableDate
import com.bustramnantes.app.presentation.search.DepartureTimeMode
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * "Maintenant" / "Partir à" / "Arriver à" chooser for an itinerary search.
 *
 * All three modes are planned on the device (forward RAPTOR for "Maintenant"/"Partir à", reverse for
 * "Arriver à").
 *
 * Mirrors [SearchModeSegmentedControl]'s segmented-button look (rather than plain radio rows). The
 * date/time chips are shown/hidden instantly when leaving "Maintenant" - an animated reveal janked
 * badly inside the AlertDialog (its window can't resize smoothly per frame).
 *
 * The selectable dates are `[today, lastSchedulableDate(feedEnd)]` - at most
 * [com.bustramnantes.app.domain.util.MAX_SCHEDULE_DAYS_AHEAD] days, and never past the installed
 * timetable's last day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepartureTimePickerDialog(
    initialMode: DepartureTimeMode,
    initialDateTime: LocalDateTime?,
    onConfirm: (DepartureTimeMode, LocalDateTime?) -> Unit,
    onDismiss: () -> Unit,
    feedEnd: LocalDate? = null,
) {
    val now = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()) }
    val today = now.date
    val lastSelectable = remember(today, feedEnd) { today.lastSchedulableDate(feedEnd) }
    var mode by remember { mutableStateOf(initialMode) }
    // Coerced into the window: a date carried over from an earlier search can have gone stale (and
    // `rememberDatePickerState` throws outright if its initial selection falls outside `yearRange`).
    var pickedDate by remember { mutableStateOf((initialDateTime?.date ?: today).coerceIn(today, lastSelectable)) }
    var pickedHour by remember { mutableStateOf(initialDateTime?.hour ?: now.hour) }
    var pickedMinute by remember { mutableStateOf(initialDateTime?.minute ?: now.minute) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quand souhaitez-vous voyager ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    DepartureTimeMode.entries.forEachIndexed { index, entry ->
                        SegmentedButton(
                            selected = mode == entry,
                            onClick = { mode = entry },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = DepartureTimeMode.entries.size,
                            ),
                            // Three segments only fit on a narrow phone once the default
                            // selected-state check icon (and the space it reserves) is gone; with
                            // it, "Maintenant" ellipsises at 360dp. The smaller label style buys
                            // the remaining margin.
                            icon = {},
                        ) {
                            Text(
                                text = entry.label(),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }

                // Shown/hidden instantly (no expand/fade transition). An animated reveal here janks
                // badly because it runs inside an AlertDialog, whose window can't resize smoothly
                // frame-by-frame - the chips appeared to stutter in slowly. Instant is smooth.
                if (mode != DepartureTimeMode.NOW) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = true,
                            onClick = { showDatePicker = true },
                            label = { Text("${pickedDate.dayOfMonth.pad2()}/${pickedDate.monthNumber.pad2()}/${pickedDate.year}") },
                            leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        )
                        FilterChip(
                            selected = true,
                            onClick = { showTimePicker = true },
                            label = { Text("${pickedHour.pad2()}:${pickedMinute.pad2()}") },
                            leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val dateTime = if (mode == DepartureTimeMode.NOW) {
                        null
                    } else {
                        LocalDateTime(pickedDate, LocalTime(pickedHour, pickedMinute))
                    }
                    onConfirm(mode, dateTime)
                },
            ) { Text("Valider") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )

    if (showDatePicker) {
        // Clamped to [today, lastSelectable]: past the installed timetable there is no transit at
        // all, which reads to the user as "this app only knows how to walk". The *time* of day is
        // deliberately NOT clamped - an earlier time on today's date still plans buses, and blocking
        // it would break the legitimate "what did I miss this morning" lookup.
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = pickedDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            yearRange = today.year..lastSelectable.year,
            selectableDates = remember(today) { ScheduleWindow(today, lastSelectable) },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        pickedDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Annuler") } },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(initialHour = pickedHour, initialMinute = pickedMinute, is24Hour = true)
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Choisir une heure",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    TimePicker(state = timePickerState)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showTimePicker = false }) { Text("Annuler") }
                        TextButton(
                            onClick = {
                                pickedHour = timePickerState.hour
                                pickedMinute = timePickerState.minute
                                showTimePicker = false
                            },
                        ) { Text("OK") }
                    }
                }
            }
        }
    }
}

/**
 * Greys out every day outside `[first, last]` in the Material date picker. The picker hands over
 * **UTC-midnight** millis, so both bounds are converted the same way rather than via the device
 * time zone (which would shift the boundary day by a few hours).
 */
@OptIn(ExperimentalMaterial3Api::class)
private class ScheduleWindow(first: LocalDate, last: LocalDate) : SelectableDates {
    private val firstMillis = first.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    private val lastMillis = last.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    private val years = first.year..last.year

    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in firstMillis..lastMillis

    override fun isSelectableYear(year: Int): Boolean = year in years
}

/** Short on purpose - three segments have to fit side by side on a narrow phone. */
private fun DepartureTimeMode.label(): String = when (this) {
    DepartureTimeMode.NOW -> "Maintenant"
    DepartureTimeMode.DEPART_AT -> "Partir à"
    DepartureTimeMode.ARRIVE_BY -> "Arriver à"
}

private fun Int.pad2() = toString().padStart(2, '0')
