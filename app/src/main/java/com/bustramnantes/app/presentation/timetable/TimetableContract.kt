package com.bustramnantes.app.presentation.timetable

import com.bustramnantes.app.domain.model.TimetableEntry
import com.bustramnantes.app.domain.model.TransportMode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * State for [TimetableViewModel] (one `UiState` per screen). [isLoading] is `true` while the
 * (local, fast) timetable computation runs. [isEmptyForDate] covers both a genuinely empty service
 * day and a date outside the installed GTFS feed.
 */
data class TimetableUiState(
    val stopName: String,
    val lineNumber: String,
    val lineColor: String = "",
    val lineTextColor: String = "",
    val transportMode: TransportMode = TransportMode.BUS,
    val directionName: String = "",
    val date: LocalDate,
    val isLoading: Boolean = true,
    val hoursGrouped: List<HourBlock> = emptyList(),
    /** Distinct terminus labels for the day; > 1 means the direction has branch splits worth captioning. */
    val destinationLabels: List<String> = emptyList(),
    val isEmptyForDate: Boolean = false,
    val errorMessage: String? = null,
    /** The itinerary leg's departure to emphasise (null from Stop Detail). */
    val highlightTime: LocalTime? = null,
    /** Resolved match for [highlightTime] (exact or nearest); the grid renders it distinctly. */
    val highlightedEntry: TimetableEntry? = null,
    /** `false` when [highlightedEntry] is a nearest-match fallback (no exact departure at [highlightTime]). */
    val highlightIsExact: Boolean = true,
) {
    val hasMultipleDestinations: Boolean get() = destinationLabels.size > 1
}

sealed interface TimetableEvent {
    data class DateSelected(val date: LocalDate) : TimetableEvent
    data object Retry : TimetableEvent
}

/**
 * One row of the timetable grid: a maximal run of consecutive hours that share the same minutes (and
 * per-minute destinations) is collapsed into a single [startHour]..[endHour] range - see
 * [groupIntoHourBlocks]. [entries] is that shared minute set (identical for every hour in the run).
 */
data class HourBlock(
    val startHour: Int,
    val endHour: Int,
    /** True for post-midnight service (rendered in its own section below the day's runs). */
    val nextDay: Boolean,
    val entries: List<TimetableEntry>,
) {
    val label: String
        get() = if (startHour == endHour) "${pad(startHour)}h" else "${pad(startHour)}h–${pad(endHour)}h"

    private fun pad(h: Int): String = h.toString().padStart(2, '0')
}

/**
 * Groups chronological [entries] into hour rows, collapsing **consecutive** hours whose minute set
 * *and* per-minute destination labels are identical into a single range row (e.g. `12h–16h`). This
 * is lossless (the minutes match for every hour in the run). Never merges across the after-midnight
 * boundary. When [isolateHour] is given (the highlighted departure's `nextDay to hour`), that hour is
 * kept as its own row so the emphasised minute chip is unambiguous.
 */
fun groupIntoHourBlocks(
    entries: List<TimetableEntry>,
    isolateHour: Pair<Boolean, Int>? = null,
): List<HourBlock> {
    if (entries.isEmpty()) return emptyList()

    data class HourBucket(val nextDay: Boolean, val hour: Int, val entries: List<TimetableEntry>)

    val buckets = entries
        .groupBy { it.nextDay to it.time.hour }
        .map { (key, value) -> HourBucket(key.first, key.second, value.sortedBy { it.time }) }
        .sortedWith(compareBy({ it.nextDay }, { it.hour }))

    fun signature(bucket: HourBucket): List<Pair<Int, String?>> =
        bucket.entries.map { it.time.minute to it.destinationLabel }

    val blocks = mutableListOf<HourBlock>()
    var i = 0
    while (i < buckets.size) {
        val start = buckets[i]
        var j = i
        while (
            j + 1 < buckets.size &&
            buckets[j + 1].nextDay == start.nextDay &&
            buckets[j + 1].hour == buckets[j].hour + 1 &&
            signature(buckets[j + 1]) == signature(start) &&
            (start.nextDay to start.hour) != isolateHour &&
            (buckets[j + 1].nextDay to buckets[j + 1].hour) != isolateHour
        ) {
            j++
        }
        blocks += HourBlock(start.hour, buckets[j].hour, start.nextDay, start.entries)
        i = j + 1
    }
    return blocks
}
