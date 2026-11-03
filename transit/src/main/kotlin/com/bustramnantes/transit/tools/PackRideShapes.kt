package com.bustramnantes.transit.tools

import com.bustramnantes.transit.NetworkFormat
import com.bustramnantes.transit.withRideShapes
import java.io.File

/**
 * Adds the street paths of every ride hop ([com.bustramnantes.transit.RideShapes]) to the bundled
 * network `app/src/main/assets/seed/network.bin`, in place:
 * `./gradlew :transit:packRideShapes -Posm=/path/pays-de-la-loire-YYMMDD.osm.pbf` (the Geofabrik
 * extract `packWalkGraph` uses - never the live OSM API). `packSeed` carries the shapes of the
 * previous seed over to a new GTFS by itself; run this after it when lines change course or new
 * hops appear (it prints how many hops are drawn straight).
 */
fun main(args: Array<String>) {
    val file = File(args[0])
    val pbf = File(requireNotNull(args.getOrNull(1)?.takeIf { it.isNotBlank() }) { "pass -Posm=/path/extract.osm.pbf" })
    val started = System.nanoTime()
    val network = file.inputStream().buffered().use(NetworkFormat::read)
    val (shapes, stats) = RideShapeBuilder.build(network, pbf)
    val tmp = File(file.path + ".tmp")
    tmp.outputStream().buffered().use { NetworkFormat.write(network.withRideShapes(shapes), it) }
    tmp.inputStream().buffered().use(NetworkFormat::read) // never install a file that doesn't load
    check(tmp.renameTo(file)) { "cannot replace ${file.path}" }
    println(
        "wrote ${file.path} (${file.length() / 1024} KB) in ${"%.0f".format((System.nanoTime() - started) / 1e9)} s: " +
            "${stats.hops} hops, ${stats.routed} routed, ${stats.straight} straight (+ ferries); ${shapes.pointCount} points; " +
            "${stats.linesWithRelation}/${stats.lines} lines have an OSM route relation",
    )
}
