package com.bustramnantes.app.domain.model

import kotlinx.datetime.Instant

/** A time range a [Disruption] applies to; `null` = open-ended on that side. */
data class DisruptionPeriod(val start: Instant?, val end: Instant?) {
    fun contains(t: Instant): Boolean = (start == null || t >= start) && (end == null || t <= end)
    fun overlaps(from: Instant, to: Instant): Boolean = (start == null || to >= start) && (end == null || from <= end)
}

/**
 * What a [Disruption] targets: [lineId] alone = the whole line, [stopId] alone = every line at that
 * stop, both = that line at that stop. [stopId] is a GTFS StopPlace id (the feed targets quays; the
 * repository maps each quay to its StopPlace).
 */
data class DisruptionTarget(val lineId: String?, val stopId: String?)

/** GTFS-RT alert causes the Naolib feed actually uses; everything else is [OTHER]. */
enum class DisruptionCause { CONSTRUCTION, DEMONSTRATION, OTHER }

/**
 * A network disruption notice (works, diversion, moved stop, demonstration…) from the Okina GTFS-RT
 * Alerts feed, cleaned up by `ServiceAlerts.clean` (blank twins folded in, lines named in the text
 * inferred for untargeted notices).
 *
 * [descriptionHtml] is the publisher's HTML (render with `AnnotatedString.fromHtml`); [title] is never
 * blank (falls back to the description's first words). A disruption without [targets] concerns no
 * line or stop in particular and only shows in the "Perturbations" list.
 */
data class Disruption(
    val id: String,
    val title: String,
    val descriptionHtml: String,
    val url: String?,
    val periods: List<DisruptionPeriod>,
    val targets: List<DisruptionTarget>,
    val cause: DisruptionCause,
) {
    val lineIds: Set<String> get() = targets.mapNotNullTo(LinkedHashSet()) { it.lineId }
    val stopIds: Set<String> get() = targets.mapNotNullTo(LinkedHashSet()) { it.stopId }

    fun isActiveAt(t: Instant): Boolean = periods.isEmpty() || periods.any { it.contains(t) }
    fun overlaps(from: Instant, to: Instant): Boolean = periods.isEmpty() || periods.any { it.overlaps(from, to) }

    /** Every period ended before [t]: nothing left to show. */
    fun isOverAt(t: Instant): Boolean = periods.isNotEmpty() && periods.all { it.end != null && it.end < t }

    /** The first period start after [t] (when not active at [t]), for "à partir du …". */
    fun nextStartAfter(t: Instant): Instant? = periods.mapNotNull { it.start }.filter { it > t }.minOrNull()

    /** Targets [stopIds] (any StopPlace of a merged stop), with or without a line. */
    fun targetsStop(stopIds: Collection<String>): Boolean = targets.any { it.stopId != null && it.stopId in stopIds }

    /** Targets [lineId] as a whole (not just at some stop). */
    fun targetsWholeLine(lineId: String): Boolean = targets.any { it.lineId == lineId && it.stopId == null }

    /** Concerns [lineId] somewhere: as a whole or at some stop. */
    fun concernsLine(lineId: String): Boolean = targets.any { it.lineId == lineId }

    /** Concerns [lineId] at one of [stopIds]: the whole line, that line there, or every line there. */
    fun concernsLineAt(lineId: String, stopIds: Collection<String>): Boolean = targets.any { t ->
        (t.lineId == lineId && (t.stopId == null || t.stopId in stopIds)) ||
            (t.lineId == null && t.stopId != null && t.stopId in stopIds)
    }
}
