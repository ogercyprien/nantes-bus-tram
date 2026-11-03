package com.bustramnantes.app.presentation.itinerary.map

import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection

/**
 * Options colours on the results map (and the matching bar on each result card): distinct hues,
 * readable on the light basemap and against each other; cycled past eight.
 */
val OPTION_COLORS = listOf("#1E6FD9", "#E8590C", "#2F9E44", "#9C36B5", "#C92A2A", "#0C8599", "#5C940D", "#D6336C")

/**
 * One route on the results map: the journeys of the list that take it ([journeyIndices], list order
 * - the same lines between the same stops, at different times), drawn once, in [color].
 */
data class OptionRoute(
    val journeyIndices: List<Int>,
    val color: String,
    val legs: List<JourneyMapLeg>,
    val origin: MapPoint,
    val destination: MapPoint,
)

/**
 * The results list's journeys grouped by route ([routeKey]) in list order, each route with its colour
 * and geometry ([journeyMapModel]). Journeys without drawable geometry are left out.
 */
fun optionRoutes(journeys: List<Journey>): List<OptionRoute> {
    val groups = LinkedHashMap<List<String>, MutableList<Int>>()
    journeys.forEachIndexed { i, j -> groups.getOrPut(routeKey(j)) { ArrayList() } += i }
    return groups.values.mapNotNull { indices -> journeyMapModel(journeys[indices.first()])?.let { indices to it } }
        .mapIndexed { k, (indices, model) ->
            OptionRoute(indices, OPTION_COLORS[k % OPTION_COLORS.size], model.legs, model.origin, model.destination)
        }
}

/** What makes two journeys the same route on the map: their rides (line, boarding and alighting stop). */
internal fun routeKey(journey: Journey): List<String> {
    val rides = journey.sections.filterIsInstance<JourneySection.Transit>()
        .map { "${it.lineNumber}|${it.concernedStops.firstOrNull()?.stopId}|${it.concernedStops.lastOrNull()?.stopId}" }
    return rides.ifEmpty { listOf("walk") }
}

/** The route (index into [routes]) each journey index belongs to. */
fun routeOfJourney(routes: List<OptionRoute>): Map<Int, Int> =
    routes.flatMapIndexed { r, route -> route.journeyIndices.map { it to r } }.toMap()
