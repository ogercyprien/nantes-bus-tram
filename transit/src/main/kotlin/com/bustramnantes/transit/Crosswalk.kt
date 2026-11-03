package com.bustramnantes.transit

import com.bustramnantes.transit.gtfs.CrosswalkEntry
import com.bustramnantes.transit.walk.Footpaths
import java.text.Normalizer

/**
 * One stop of the official map's `logical_stops.geojson`: its `external_id` (the geojson's own key),
 * name, position and `linked_lines`.
 */
data class OfficialStop(
    val externalId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val lines: Set<String>,
)

/**
 * Builds the crosswalk official map stop -> GTFS StopPlace at seed time, for **display positions**:
 * the raster line tiles are drawn against the official stop positions, which differ from the GTFS
 * ones by a few metres to a few tens of metres - enough for a marker to sit beside its line. The
 * official list has no GTFS id, so the match is geometric + by name/lines.
 *
 * **StopPlace-centred** (each StopPlace picks its official stop), because GTFS is finer than the
 * geojson where the official map merges stops: e.g. geojson "Chanzy" (C1;C6;NN) is GTFS "Chanzy" (C1,
 * NN) + "Bretéché" (C6); "Route de la Chapelle" (59;C2;109) is "Route de la Chapelle" (C2) + "Picabia"
 * (59, 109); "Lieu Unique" (4;C2;…) is "Lieu Unique" + "Duchesse Anne - Château" (4). Rules, first hit
 * wins:
 *  1. same [looseName] (accents, case, punctuation, `St`/`Ste` folded), nearest within [NAME_RADIUS_M];
 *     the five stops whose geojson name lags the current one ([STALE_NAMES]) also match under their
 *     current name;
 *  2. nearest official stop within [LINES_RADIUS_M] whose lines are a superset of the StopPlace's.
 * On the real network 1,098 / 1,101 StopPlaces match (median 18 m, p95 85 m); the rest keep their GTFS
 * coordinates.
 *
 * The geojson position is kept only when exactly one StopPlace took that official stop by name; split
 * stops (and stops matched by lines) use their own GTFS positions.
 */
object Crosswalk {
    const val NAME_RADIUS_M = 600.0
    const val LINES_RADIUS_M = 150.0

    /**
     * Official stops whose geojson name lags the current one - external_id to current name. They
     * match a StopPlace under either name.
     */
    val STALE_NAMES: Map<String, String> = mapOf(
        "59582" to "Bretéché",
        "61766" to "Bouaye",
        "65357" to "Duchesse Anne-Chateau",
        "60877" to "Picabia",
        "45493" to "St Paul Salengro",
    )

    /**
     * Former names to keep searchable, keyed by StopPlace number. Intentionally **empty**: GTFS names
     * are current. Kept as the hook for a future rename riders see before the GTFS carries it.
     */
    val SEARCH_ALIASES: Map<Int, List<String>> = emptyMap()

    fun build(network: TransitNetwork, official: List<OfficialStop>): Map<Int, CrosswalkEntry> {
        val officialNames = official.map { stop ->
            setOfNotNull(looseName(stop.name), STALE_NAMES[stop.externalId]?.let(::looseName))
        }
        val placeLines = network.stopPlaces.indices.map { sp ->
            network.linkedRoutes(sp).map { network.routes[it].shortName }.toSet()
        }

        // 1-2. StopPlace-centred pick.
        val picked = IntArray(network.stopPlaces.size) { -1 }
        val byName = BooleanArray(network.stopPlaces.size)
        // Appended feeds (the bacs' landings) are not on the official map: never matched, so a landing
        // named like a nearby bus stop ("Basse-Indre") doesn't take its position.
        fun external(sp: Int) = network.stopPlaces[sp].number >= TransitNetwork.EXTERNAL_NUMBER_BASE
        network.stopPlaces.forEachIndexed { i, place ->
            if (external(i)) return@forEachIndexed
            val near = official.indices
                .map { it to haversineMeters(place.lat, place.lon, official[it].lat, official[it].lon) }
                .filter { it.second <= NAME_RADIUS_M }
                .sortedBy { it.second }
            val name = looseName(place.name)
            val hit = near.firstOrNull { (o, _) -> name in officialNames[o] }
            if (hit != null) {
                picked[i] = hit.first
                byName[i] = true
            } else {
                val lines = placeLines[i]
                picked[i] = near.firstOrNull { (o, d) ->
                    d <= LINES_RADIUS_M && lines.isNotEmpty() && official[o].lines.containsAll(lines)
                }?.first ?: -1
            }
        }

        val nameTakers = HashMap<Int, Int>()
        picked.forEachIndexed { i, o -> if (o >= 0 && byName[i]) nameTakers[o] = (nameTakers[o] ?: 0) + 1 }

        val result = HashMap<Int, CrosswalkEntry>()
        network.stopPlaces.forEachIndexed { i, place ->
            val o = picked[i]
            val keepOfficialPosition = o >= 0 && byName[i] && nameTakers[o] == 1
            val aliases = SEARCH_ALIASES[place.number].orEmpty()
            if (!keepOfficialPosition && aliases.isEmpty()) return@forEachIndexed
            result[place.number] = CrosswalkEntry(
                lat = if (keepOfficialPosition) official[o].lat else null,
                lon = if (keepOfficialPosition) official[o].lon else null,
                aliases = aliases,
            )
        }
        return result
    }

    /** Accent/case/punctuation-insensitive key, with `St`/`Ste` expanded ("Ste-Luce" == "Sainte Luce"). */
    fun looseName(name: String): String {
        val folded = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return folded
            .replace(Regex("\\bste\\b"), "sainte")
            .replace(Regex("\\bst\\b"), "saint")
            .replace(Regex("[^a-z0-9]"), "")
    }
}

/** The crosswalk currently applied to this network (to carry it over to a freshly converted feed). */
fun TransitNetwork.crosswalk(): Map<Int, CrosswalkEntry> = buildMap {
    for (sp in stopPlaces) {
        if (!sp.hasDisplayPosition && sp.aliases.isEmpty()) continue
        put(
            sp.number,
            CrosswalkEntry(
                lat = sp.lat.takeIf { sp.hasDisplayPosition },
                lon = sp.lon.takeIf { sp.hasDisplayPosition },
                aliases = sp.aliases,
            ),
        )
    }
}

/** A copy of this (crosswalk-free, GTFS-positioned) network with [crosswalk] applied to its StopPlaces. */
fun TransitNetwork.withCrosswalk(crosswalk: Map<Int, CrosswalkEntry>): TransitNetwork {
    val places = stopPlaces.map { sp ->
        val cw = crosswalk[sp.number]
        StopPlace(
            number = sp.number,
            name = sp.name,
            lat = cw?.lat ?: sp.lat,
            lon = cw?.lon ?: sp.lon,
            wheelchair = sp.wheelchair,
            quays = sp.quays,
            aliases = cw?.aliases.orEmpty(),
            hasDisplayPosition = cw?.lat != null,
        )
    }
    return TransitNetwork(
        formatVersion, feedId, validFrom, dayCount, timezone, routes, places,
        quayNumber, quayStopPlace, quayLat, quayLon, patterns,
        tripPattern, tripStart, tripProfile, tripServiceSet, tripHeadsign, headsigns,
        profileArrivals, profileDepartures, serviceSets, vjNumbers, vjTrips, shapes, footpaths, rideShapes,
    )
}

/** This network with [footpaths] (null = crow-fly fallback in the router). */
fun TransitNetwork.withFootpaths(footpaths: Footpaths?): TransitNetwork = TransitNetwork(
    formatVersion, feedId, validFrom, dayCount, timezone, routes, stopPlaces,
    quayNumber, quayStopPlace, quayLat, quayLon, patterns,
    tripPattern, tripStart, tripProfile, tripServiceSet, tripHeadsign, headsigns,
    profileArrivals, profileDepartures, serviceSets, vjNumbers, vjTrips, shapes, footpaths, rideShapes,
)

/** This network with [rideShapes] (null = rides drawn stop to stop). */
fun TransitNetwork.withRideShapes(rideShapes: RideShapes?): TransitNetwork = TransitNetwork(
    formatVersion, feedId, validFrom, dayCount, timezone, routes, stopPlaces,
    quayNumber, quayStopPlace, quayLat, quayLon, patterns,
    tripPattern, tripStart, tripProfile, tripServiceSet, tripHeadsign, headsigns,
    profileArrivals, profileDepartures, serviceSets, vjNumbers, vjTrips, shapes, footpaths, rideShapes,
)
