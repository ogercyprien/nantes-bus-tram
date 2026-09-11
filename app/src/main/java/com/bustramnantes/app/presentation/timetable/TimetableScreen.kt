package com.bustramnantes.app.presentation.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bustramnantes.app.domain.model.TimetableEntry
import com.bustramnantes.app.presentation.common.components.EmptyState
import com.bustramnantes.app.presentation.common.components.LineBadge
import com.bustramnantes.app.presentation.common.components.LineBadgeSize
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/** How far ahead the date picker allows selecting (§9.2). Data reaches the service-period edge; +60 covers it with headroom. */
private const val DATE_PICKER_HORIZON_DAYS = 60

private val PARIS: TimeZone = TimeZone.of("Europe/Paris")

/**
 * "Horaires théoriques" screen: the full scheduled timetable for one line+direction at a stop on a
 * chosen date. Reachable from the Stop Detail sheet and from an
 * itinerary transit leg (the latter passes a `highlight` time that's emphasised in the grid).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimetableViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { TimetableTitle(uiState) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            DateSelectorRow(date = uiState.date, onClick = { showDatePicker = true })
            HorizontalDivider()
            TimetableBody(
                uiState = uiState,
                onRetry = { viewModel.onEvent(TimetableEvent.Retry) },
            )
        }
    }

    if (showDatePicker) {
        TimetableDatePicker(
            selected = uiState.date,
            onDismiss = { showDatePicker = false },
            onConfirm = { date ->
                showDatePicker = false
                if (date != uiState.date) viewModel.onEvent(TimetableEvent.DateSelected(date))
            },
        )
    }
}

@Composable
private fun TimetableTitle(uiState: TimetableUiState) {
    Column {
        Text(
            text = uiState.stopName.ifBlank { "Horaires" },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineBadge(
                shortName = uiState.lineNumber,
                backgroundColorHex = uiState.lineColor,
                textColorHex = uiState.lineTextColor,
                mode = uiState.transportMode,
                size = LineBadgeSize.Small,
            )
            if (uiState.directionName.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = uiState.directionName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DateSelectorRow(date: LocalDate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onClick,
            label = { Text(formatDateLabel(date)) },
            leadingIcon = {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.width(18.dp),
                )
            },
            colors = AssistChipDefaults.assistChipColors(),
        )
    }
}

@Composable
private fun TimetableBody(uiState: TimetableUiState, onRetry: () -> Unit) {
    when {
        uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        uiState.errorMessage != null -> EmptyState(
            icon = Icons.Filled.EventBusy,
            message = uiState.errorMessage,
            ctaLabel = "Réessayer",
            onCta = onRetry,
        )

        uiState.isEmptyForDate -> EmptyState(
            icon = Icons.Filled.EventBusy,
            message = "Aucun horaire pour cette date ou les horaires ne sont pas encore publiés",
        )

        else -> TimetableGrid(uiState)
    }
}

@Composable
private fun TimetableGrid(uiState: TimetableUiState) {
    val listState = rememberLazyListState()
    val blocks = uiState.hoursGrouped
    val highlighted = uiState.highlightedEntry

    // Auto-scroll to the highlighted departure's block on first composition (itinerary entry point).
    val highlightBlockIndex = remember(blocks, highlighted) {
        if (highlighted == null) -1 else blocks.indexOfFirst { block -> block.entries.any { it == highlighted } }
    }
    if (highlightBlockIndex >= 0) {
        androidx.compose.runtime.LaunchedEffect(highlightBlockIndex) {
            listState.scrollToItem(highlightBlockIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        blocks.forEachIndexed { index, block ->
            // Divider heading the after-midnight section (first nextDay block).
            if (block.nextDay && (index == 0 || !blocks[index - 1].nextDay)) {
                item(key = "after-midnight") { AfterMidnightDivider() }
            }
            item(key = "${block.nextDay}-${block.startHour}-${block.endHour}") {
                HourBlockRow(
                    block = block,
                    highlighted = highlighted,
                    showDestinations = uiState.hasMultipleDestinations,
                )
            }
        }
    }
}

@Composable
private fun AfterMidnightDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.width(24.dp))
        Text(
            text = "  après minuit  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HourBlockRow(
    block: HourBlock,
    highlighted: TimetableEntry?,
    showDestinations: Boolean,
) {
    val containsHighlight = highlighted != null && block.entries.any { it == highlighted }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = block.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp).padding(top = 4.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                block.entries.forEach { entry ->
                    MinuteChip(
                        entry = entry,
                        isHighlighted = highlighted != null && entry == highlighted,
                        showDestination = showDestinations,
                    )
                }
            }
            if (containsHighlight) {
                Text(
                    text = "Votre départ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MinuteChip(
    entry: TimetableEntry,
    isHighlighted: Boolean,
    showDestination: Boolean,
) {
    val background = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (isHighlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = entry.time.minute.toString().padStart(2, '0'),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
            color = foreground,
        )
        if (showDestination && !entry.destinationLabel.isNullOrBlank()) {
            Text(
                text = entry.destinationLabel,
                style = MaterialTheme.typography.labelSmall,
                color = foreground,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimetableDatePicker(
    selected: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val today = remember { Clock.System.now().toLocalDateTime(PARIS).date }
    val minMillis = remember(today) { today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }
    val maxMillis = remember(today) {
        LocalDate.fromEpochDays(today.toEpochDays() + DATE_PICKER_HORIZON_DAYS)
            .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        yearRange = today.year..(today.year + 1),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in minMillis..maxMillis
            override fun isSelectableYear(year: Int): Boolean = year in today.year..(today.year + 1)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        onConfirm(Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date)
                    } else {
                        onDismiss()
                    }
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    ) {
        DatePicker(state = state)
    }
}

/** "Aujourd'hui" for today, else "dd/MM/yyyy" (Paris calendar). */
private fun formatDateLabel(date: LocalDate): String {
    val today = Clock.System.now().toLocalDateTime(PARIS).date
    if (date == today) return "Aujourd'hui"
    val d = date.dayOfMonth.toString().padStart(2, '0')
    val m = date.monthNumber.toString().padStart(2, '0')
    return "$d/$m/${date.year}"
}
