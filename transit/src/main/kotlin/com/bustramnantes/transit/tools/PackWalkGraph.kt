package com.bustramnantes.transit.tools

import com.bustramnantes.transit.walk.WalkGraphFormat
import java.io.File

/**
 * Regenerates the bundled walking graph `app/src/main/assets/seed/walk_graph.bin` from a Geofabrik
 * extract: `./gradlew :transit:packWalkGraph -Posm=/path/pays-de-la-loire-YYMMDD.osm.pbf`
 * (https://download.geofabrik.de/europe/france/pays-de-la-loire.html - use the pre-packaged extract,
 * never the live OSM API). Then run `:transit:packSeed` so the stop-to-stop walks in `network.bin`
 * are recomputed on the new graph.
 */
fun main(args: Array<String>) {
    val out = File(args[0])
    val pbf = File(requireNotNull(args.getOrNull(1)?.takeIf { it.isNotBlank() }) { "pass -Posm=/path/extract.osm.pbf" })
    val started = System.nanoTime()
    val (graph, stats) = WalkGraphBuilder.build(pbf, source = pbf.name.removeSuffix(".osm.pbf"))
    out.parentFile.mkdirs()
    out.outputStream().buffered().use { WalkGraphFormat.write(graph, it) }
    val seconds = (System.nanoTime() - started) / 1e9
    println(
        "wrote ${out.path} (${out.length() / 1024} KB) in ${"%.0f".format(seconds)} s: ${stats.vertices} vertices, " +
            "${stats.edges} edges, ${"%.0f".format(stats.totalMeters / 1000)} km walkable; " +
            "${graph.geomLat.size} shape points; dropped ${stats.droppedComponents} islands",
    )
}
