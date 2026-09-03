package com.bustramnantes.app.domain.model

/**
 * A line that serves a given stop, together with every [directions] that line actually runs *at
 * that stop*. Built from the GTFS patterns, which - unlike the live board - list a stop's
 * lines/directions regardless of whether a bus is due right now. This is what lets Stop Detail keep
 * showing a line/direction after its last departure of the day, with a "fin de service" note,
 * instead of the row silently vanishing.
 *
 * At a terminus a line legitimately has a single entry in [directions] (a direction the stop only
 * ends is left out), so no special-casing is needed downstream.
 *
 * [lineColor] / [lineTextColor] keep the `#`-prefixed form, matching
 * [Departure.lineColor] (not the un-prefixed [Line.color]) so both can feed the same badge.
 */
data class ServedLine(
    val lineId: String,
    val lineNumber: String,
    val lineName: String,
    val lineColor: String,
    val lineTextColor: String,
    val transportMode: TransportMode,
    val directions: List<Direction>,
)
