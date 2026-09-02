package com.bustramnantes.transit.tools

import com.bustramnantes.transit.Crosswalk
import com.bustramnantes.transit.NetworkFormat
import com.bustramnantes.transit.OfficialStop
import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.BacsDeLoire
import com.bustramnantes.transit.gtfs.GtfsSource
import com.bustramnantes.transit.gtfs.appending
import com.bustramnantes.transit.withCrosswalk
import com.bustramnantes.transit.withFootpaths
import com.bustramnantes.transit.walk.Footpaths
import com.bustramnantes.transit.walk.WalkGraphFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI

/**
 * Regenerates the bundled offline network `app/src/main/assets/seed/network.bin`:
 * `./gradlew :transit:packSeed` (downloads the current GTFS) or `-Pgtfs=/path/gtfs.zip`.
 *
 * Steps: resolve the current GTFS publication through the open-data records API (the download URL's
 * file id changes on every publication - never hard-code it), append the Bacs de Loire
 * ([BacsDeLoire]), convert it, build the display-position [Crosswalk] against the official map's stop
 * list `transit/seed/logical_stops.geojson`, compute the quay-to-quay [Footpaths] on the bundled
 * walking graph (`assets/seed/walk_graph.bin`, see `PackWalkGraph`), and write the packed file.
 * Prints how many StopPlaces took the official position.
 */
fun main(args: Array<String>) {
    val out = File(args[0])
    val geojson = File(args[1])
    val gtfsArg = args.getOrNull(2)?.takeIf { it.isNotBlank() }
    val (zip, feedId) = if (gtfsArg != null) {
        File(gtfsArg) to File(gtfsArg).nameWithoutExtension
    } else {
        val feed = resolveFeed()
        println("GTFS ${feed.fileId}, valid ${feed.validFrom} .. ${feed.validUntil}")
        val tmp = File.createTempFile("gtfs", ".zip").apply { deleteOnExit() }
        URI(feed.url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp to feed.fileId
    }
    // The Bacs de Loire (Département 44, not in the Naolib GTFS): current zip from the portal, also
    // bundled as `assets/seed/bacs_de_loire.zip` - the on-device conversion's fallback.
    val bacs = File(out.parentFile, BACS_ASSET_NAME)
    runCatching {
        val url = requireNotNull(BacsDeLoire.zipUrlFrom(URI(BacsDeLoire.EXPORTS_URL).toURL().readText())) { "no bacs zip listed" }
        URI(url).toURL().openStream().use { input -> bacs.outputStream().use { input.copyTo(it) } }
        println("bacs de Loire: $url (${bacs.length()} bytes)")
    }.onFailure { println("bacs de Loire: download failed (${it.message}), using the bundled ${bacs.name}") }
    val source = if (bacs.isFile) GtfsSource.zip(zip).appending(GtfsSource.zip(bacs), BacsDeLoire.SPEC) else GtfsSource.zip(zip)
    val raw = GtfsConverter(source, feedId = feedId).convert()
    val official = readOfficialStops(geojson)
    val crosswalk = Crosswalk.build(raw, official)
    val walkGraph = args.getOrNull(3)?.let(::File)?.takeIf { it.isFile }?.inputStream()?.use(WalkGraphFormat::read)
    val footpaths = walkGraph?.let { Footpaths.compute(raw, it) }
    val network = raw.withCrosswalk(crosswalk).withFootpaths(footpaths)
    out.parentFile.mkdirs()
    out.outputStream().buffered().use { NetworkFormat.write(network, it) }

    println("wrote ${out.path} (${out.length() / 1024} KB): ${network.stopPlaces.size} stop places, ${network.tripCount} trips, valid ${network.validFrom}..${network.validUntil}")
    println("stop places at their official map position: ${network.stopPlaces.count { it.hasDisplayPosition }} of ${network.stopPlaces.size}")
    println(if (footpaths != null) "footpaths: ${footpaths.pairCount} quay pairs on walk graph ${walkGraph.source}" else "footpaths: none (no walk graph)")
}

private const val BACS_ASSET_NAME = "bacs_de_loire.zip"

private data class Feed(val fileId: String, val url: String, val validFrom: String, val validUntil: String)

private const val RECORDS_URL =
    "https://data.nantesmetropole.fr/api/explore/v2.1/catalog/datasets/" +
        "244400404_transports_commun_naolib_nantes_metropole_gtfs/records?limit=20"

private fun resolveFeed(): Feed {
    val text = URI(RECORDS_URL).toURL().readText()
    val record = Json.parseToJsonElement(text).jsonObject["results"]!!.jsonArray
        .map { it.jsonObject }
        .maxBy { it["debut_de_validite"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    val file = record["fichier"]!!.jsonObject
    return Feed(
        fileId = file["id"]!!.jsonPrimitive.content,
        url = file["url"]!!.jsonPrimitive.content,
        validFrom = record["debut_de_validite"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        validUntil = record["fin_de_validite"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}

private fun readOfficialStops(file: File): List<OfficialStop> =
    Json.parseToJsonElement(file.readText()).jsonObject["features"]!!.jsonArray.mapNotNull { f ->
        val props = f.jsonObject["properties"] as? JsonObject ?: return@mapNotNull null
        val coords = f.jsonObject["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
        val ext = props["external_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        OfficialStop(
            externalId = ext,
            name = props["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            lat = coords[1].jsonPrimitive.double,
            lon = coords[0].jsonPrimitive.double,
            lines = props["linked_lines"]?.jsonPrimitive?.contentOrNull.orEmpty().split(';').filter { it.isNotBlank() }.toSet(),
        )
    }
