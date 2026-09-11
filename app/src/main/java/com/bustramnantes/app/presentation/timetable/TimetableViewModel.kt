package com.bustramnantes.app.presentation.timetable

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.TimetableEntry
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.usecase.GetTheoreticalTimetableUseCase
import com.bustramnantes.app.domain.usecase.directionIdFromCode
import com.bustramnantes.app.presentation.common.utils.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Nantes local time zone - "today" and all timetable clock times are expressed in it. */
private val PARIS: TimeZone = TimeZone.of("Europe/Paris")

/**
 * Drives [TimetableScreen]. Reads the `timetable/{stopId}/{lineNumber}/{directionId}?…` route args
 * ([com.bustramnantes.app.presentation.common.navigation.Screen.Timetable]) from [SavedStateHandle];
 * resolves the line's identity/direction name from the local line catalog (so nothing visual needs to
 * be threaded through the route), then computes the theoretical timetable from the local GTFS
 * network (instant, offline).
 */
@HiltViewModel
class TimetableViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getTheoreticalTimetableUseCase: GetTheoreticalTimetableUseCase,
    private val lineRepository: LineRepository,
) : ViewModel() {

    private val stopId: String = savedStateHandle.get<String>("stopId").orEmpty()
    private val lineNumber: String = savedStateHandle.get<String>("lineNumber").orEmpty()
    private val directionId: Int? = savedStateHandle.get<String>("directionId")?.toIntOrNull()
    private val stopName: String = savedStateHandle.get<String>("stopName").orEmpty()
    private val highlightTime: LocalTime? = savedStateHandle.get<String>("highlight")?.let(::parseHhmm)
    private val initialDate: LocalDate = savedStateHandle.get<String>("date")
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: todayInParis()

    private val _uiState = MutableStateFlow(
        TimetableUiState(
            stopName = stopName,
            lineNumber = lineNumber,
            date = initialDate,
            highlightTime = highlightTime,
            isLoading = true,
        ),
    )
    val uiState: StateFlow<TimetableUiState> = _uiState.asStateFlow()

    init {
        resolveLineIdentity()
        loadTimetable(initialDate)
    }

    fun onEvent(event: TimetableEvent) {
        when (event) {
            is TimetableEvent.DateSelected -> loadTimetable(event.date)
            TimetableEvent.Retry -> loadTimetable(_uiState.value.date)
        }
    }

    /** Fills the header's line badge colors/mode and the direction name from the local line cache. */
    private fun resolveLineIdentity() {
        viewModelScope.launch {
            val line = lineRepository.getLineByShortName(lineNumber) ?: return@launch
            val directionName = line.directions
                .firstOrNull { directionIdFromCode(it.code) == directionId }
                ?.name
                .orEmpty()
            _uiState.update {
                it.copy(
                    lineColor = line.color,
                    lineTextColor = line.textColor,
                    transportMode = line.mode,
                    directionName = directionName,
                )
            }
        }
    }

    private fun loadTimetable(date: LocalDate) {
        if (stopId.isBlank() || directionId == null || lineNumber.isBlank()) {
            // Can't address the timetable without these; render the empty state rather than an error.
            _uiState.update { it.copy(date = date, isLoading = false, isEmptyForDate = true, hoursGrouped = emptyList()) }
            return
        }
        _uiState.update { it.copy(date = date, isLoading = true, errorMessage = null, isEmptyForDate = false) }
        viewModelScope.launch {
            when (val result = getTheoreticalTimetableUseCase(stopId, lineNumber, directionId, date)) {
                is DataResult.Success -> {
                    val timetable = result.data
                    val highlight = resolveHighlight(timetable.entries)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isEmptyForDate = timetable.isEmpty,
                            destinationLabels = timetable.destinationLabels,
                            hoursGrouped = groupIntoHourBlocks(
                                entries = timetable.entries,
                                isolateHour = highlight?.let { m -> m.nextDay to m.time.hour },
                            ),
                            highlightedEntry = highlight,
                            highlightIsExact = highlight == null || highlight.time == highlightTime && !highlight.nextDay,
                            errorMessage = null,
                        )
                    }
                }

                is DataResult.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.toUserMessage("Impossible de récupérer les horaires."),
                    )
                }
            }
        }
    }

    /**
     * Finds the entry matching [highlightTime] - exact `HH:mm` (the itinerary leg's departure is a
     * theoretical time, so it normally lands exactly), else the nearest by minute-of-day so the "gap
     * to the next run" is still visible. Null when there's no highlight or no entries.
     */
    private fun resolveHighlight(entries: List<TimetableEntry>): TimetableEntry? {
        val target = highlightTime ?: return null
        if (entries.isEmpty()) return null
        entries.firstOrNull { !it.nextDay && it.time == target }?.let { return it }
        val targetMinutes = target.hour * 60 + target.minute
        return entries.minByOrNull { abs(minuteOfDay(it) - targetMinutes) }
    }

    private fun minuteOfDay(entry: TimetableEntry): Int =
        (if (entry.nextDay) 24 * 60 else 0) + entry.time.hour * 60 + entry.time.minute

    private fun todayInParis(): LocalDate = Clock.System.now().toLocalDateTime(PARIS).date
}

/** Parses a `"HH:mm"` clock string to a [LocalTime], or null if malformed. */
private fun parseHhmm(raw: String): LocalTime? {
    val parts = raw.split(":")
    if (parts.size < 2) return null
    val hour = parts[0].trim().toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = parts[1].trim().toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    return LocalTime(hour, minute)
}
