package com.bustramnantes.transit.tools

import com.bustramnantes.transit.NetworkFormat
import com.bustramnantes.transit.RideShapes
import com.bustramnantes.transit.gtfs.BacsDeLoire
import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.GtfsPublication
import com.bustramnantes.transit.gtfs.GtfsSource
import com.bustramnantes.transit.gtfs.appending
import com.bustramnantes.transit.hopKeys
import com.bustramnantes.transit.lines.LineMapFormat
import com.bustramnantes.transit.lines.LineMapSet
import com.bustramnantes.transit.walk.Footpaths
import com.bustramnantes.transit.walk.WalkGraphFormat
import com.bustramnantes.transit.withFootpaths
import com.bustramnantes.transit.withRideShapes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * The bundled seed data - `app/src/main/assets/seed/{walk_graph.bin, network.bin, bacs_de_loire.zip, line_map.bin}` -
 * is a deterministic compilation of open data, recorded in `transit/seed/seed-manifest.json`:
 *
 * - `./gradlew :transit:buildSeed -Posm=/path/pays-de-la-loire-YYMMDD.osm.pbf [-Pgtfs=zip] [-Pbacs=zip]`
 *   rebuilds the four files from scratch and rewrites the manifest. Without `-Pgtfs` / `-Pbacs` it
 *   downloads the current GTFS (named `<publication key>.zip`, see [GtfsPublication]) and bacs zip.
 *   Every input is gathered in `build/seed-inputs/`: upload those files, names unchanged, to the
 *   GitHub release the manifest names (`archive`) - the portal keeps only the current GTFS, and
 *   Geofabrik's dated extracts don't stay online, so that release is the only lasting copy.
 * - `./gradlew :transit:verifySeed [-PseedInputs=dir]` downloads the archived inputs that are
 *   missing (to `build/seed-inputs/`), checks their sha256, rebuilds into `build/seed-verify/` and
 *   checks every output against the manifest and the committed files, byte for byte.
 *
 * Pipeline (the order matters - each step reads the previous one's output back from its file):
 * OSM extract -> walking graph ([WalkGraphBuilder]); GTFS + bacs ([BacsDeLoire]) -> network
 * ([GtfsConverter]) with the quay-to-quay [Footpaths] on that graph; OSM extract -> street path of every ride hop
 * ([RideShapeBuilder]); network with its ride paths -> the line map's layouts ([LineMapSet], drawn by
 * the Plan tab). Nothing is carried over from a previous seed, so the output depends on the
 * inputs alone. The walking graph embeds the extract's file name, the network the GTFS file name
 * (its feed id): keep both names.
 */
fun main(args: Array<String>) {
    val opts = args.drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val root = File(requireNotNull(opts["root"]) { "root=<project dir>" })
    val layout = SeedLayout(root)
    when (args.firstOrNull()) {
        "build" -> buildSeed(layout, opts)
        "verify" -> verifySeed(layout, opts)
        else -> error("usage: build|verify root=<dir> ...")
    }
}

private class SeedLayout(val root: File) {
    val assets = File(root, "app/src/main/assets/seed")
    val manifest = File(root, "transit/seed/seed-manifest.json")
    val inputs = File(root, "build/seed-inputs")
    fun relative(f: File): String = f.relativeTo(root).invariantSeparatorsPath
}

private const val WALK_GRAPH = "walk_graph.bin"
private const val NETWORK = "network.bin"
private const val BACS = "bacs_de_loire.zip"
private const val LINE_MAP = "line_map.bin"
private val OUTPUTS = listOf(WALK_GRAPH, NETWORK, BACS, LINE_MAP)

private const val GTFS_SOURCE = "data.nantesmetropole.fr dataset 244400404_transports_commun_naolib_nantes_metropole_gtfs " +
    "(Nantes Métropole / Naolib, Licence Ouverte v2.0); the file name is its feed id"
private const val BACS_SOURCE = "data.loire-atlantique.fr dataset 224400028_horaires-bacs-loire-en-loire-atlantique-gtfs " +
    "(Département de Loire-Atlantique, Licence Ouverte v2.0)"
private const val OSM_SOURCE = "Geofabrik extract https://download.geofabrik.de/europe/france/pays-de-la-loire.html " +
    "(© OpenStreetMap contributors, ODbL 1.0)"

/** Builds the four seed files from [osm], [gtfs] (feed id = its file name) and [bacs] into [outDir]. */
private fun runPipeline(osm: File, gtfs: File, bacs: File, outDir: File) {
    outDir.mkdirs()
    val walkFile = File(outDir, WALK_GRAPH)
    val (graph, ws) = WalkGraphBuilder.build(osm, source = osm.name.removeSuffix(".osm.pbf"))
    walkFile.outputStream().buffered().use { WalkGraphFormat.write(graph, it) }
    println("walk graph: ${ws.vertices} vertices, ${ws.edges} edges, ${"%.0f".format(ws.totalMeters / 1000)} km")

    val bacsOut = File(outDir, BACS)
    bacs.copyTo(bacsOut, overwrite = true)
    val source = GtfsSource.zip(gtfs).appending(GtfsSource.zip(bacsOut), BacsDeLoire.SPEC)
    val raw = GtfsConverter(source, feedId = gtfs.nameWithoutExtension).convert()
    val walkGraph = walkFile.inputStream().buffered().use(WalkGraphFormat::read)
    val network = raw
        .withFootpaths(Footpaths.compute(raw, walkGraph))
        .withRideShapes(RideShapes.carriedOver(raw, null))
    val networkFile = File(outDir, NETWORK)
    networkFile.outputStream().buffered().use { NetworkFormat.write(network, it) }
    println(
        "network: ${network.stopPlaces.size} stop places, ${network.tripCount} trips, ${raw.hopKeys().size} hops, valid ${network.validFrom}..${network.validUntil}",
    )

    val reread = networkFile.inputStream().buffered().use(NetworkFormat::read)
    val (shapes, rs) = RideShapeBuilder.build(reread, osm)
    networkFile.outputStream().buffered().use { NetworkFormat.write(reread.withRideShapes(shapes), it) }
    val shipped = networkFile.inputStream().buffered().use(NetworkFormat::read) // never ship a file that doesn't load
    println("ride paths: ${rs.hops} hops, ${rs.routed} routed, ${rs.straight} straight")

    val lineMapFile = File(outDir, LINE_MAP)
    val lineMaps = LineMapSet.build(shipped)
    lineMapFile.outputStream().buffered().use { LineMapFormat.write(lineMaps, it) }
    val reloaded = lineMapFile.inputStream().buffered().use(LineMapFormat::read)
    println("line map: " + reloaded.layouts.entries.joinToString { (z, m) -> "z$z ${m.segments.size} segments, ${m.crossings()} crossings" })
}

private fun buildSeed(layout: SeedLayout, opts: Map<String, String>) {
    val repo = requireNotNull(opts["repo"]?.takeIf { it.isNotBlank() }) { "repo=<GitHub repository URL>" }
    val osmArg = requireNotNull(opts["osm"]?.takeIf { it.isNotBlank() }) { "pass -Posm=/path/pays-de-la-loire-YYMMDD.osm.pbf" }
    layout.inputs.mkdirs()
    val osm = gather(File(osmArg), layout.inputs)
    val gtfs = opts["gtfs"]?.takeIf { it.isNotBlank() }?.let { gather(File(it), layout.inputs) } ?: run {
        val feed = resolveFeed()
        val key = GtfsPublication.key(feed.fileId, feed.validFrom, feed.validUntil)
        println("GTFS ${feed.fileId}, valid ${feed.validFrom} .. ${feed.validUntil}")
        download(feed.url, File(layout.inputs, "$key.zip"))
    }
    val bacs = opts["bacs"]?.takeIf { it.isNotBlank() }?.let { gather(File(it), layout.inputs) } ?: run {
        val url = requireNotNull(BacsDeLoire.zipUrlFrom(URI(BacsDeLoire.EXPORTS_URL).toURL().readText())) { "no bacs zip listed" }
        println("bacs de Loire: $url")
        download(url, File(layout.inputs, url.substringAfterLast('/').replace(Regex("_zip$"), ".zip")))
    }

    val work = File(layout.root, "build/seed-build").apply { deleteRecursively() }
    runPipeline(osm, gtfs, bacs, work)
    layout.assets.mkdirs()
    for (name in OUTPUTS) File(work, name).copyTo(File(layout.assets, name), overwrite = true)

    val network = File(layout.assets, NETWORK).inputStream().buffered().use(NetworkFormat::read)
    val archived = listOf("gtfs" to gtfs, "bacs" to bacs, "osm" to osm)
    val tag = "seed-" + sha256(archived.joinToString("\n") { (_, f) -> "${f.name} ${sha256(f)}" }.toByteArray()).take(12)
    val manifest = buildJsonObject {
        put(
            "about",
            "The bundled seed files are compiled from these inputs by `./gradlew :transit:buildSeed` " +
                "(transit/src/main/kotlin/com/bustramnantes/transit/tools/SeedBuild.kt); `./gradlew :transit:verifySeed` " +
                "downloads the archived inputs, rebuilds and checks every output byte for byte.",
        )
        put("archive", "$repo/releases/tag/$tag")
        put("archiveDownload", "$repo/releases/download/$tag/")
        put("feedId", network.feedId)
        put("networkValidity", "${network.validFrom}..${network.validUntil}")
        put(
            "inputs",
            buildJsonArray {
                fun input(role: String, file: File, path: String, source: String, archivedFile: Boolean) = add(
                    buildJsonObject {
                        put("role", role)
                        put("file", path)
                        put("sha256", sha256(file))
                        put("size", file.length())
                        put("archived", archivedFile)
                        put("source", source)
                    },
                )
                input("gtfs", gtfs, gtfs.name, GTFS_SOURCE, true)
                input("bacs", bacs, bacs.name, BACS_SOURCE, true)
                input("osm", osm, osm.name, OSM_SOURCE, true)
            },
        )
        put(
            "outputs",
            buildJsonArray {
                for (name in OUTPUTS) {
                    val f = File(layout.assets, name)
                    add(
                        buildJsonObject {
                            put("file", layout.relative(f))
                            put("sha256", sha256(f))
                            put("size", f.length())
                        },
                    )
                }
            },
        )
    }
    layout.manifest.writeText(PRETTY.encodeToString(JsonElement.serializer(), manifest) + "\n")
    println("wrote ${layout.relative(layout.manifest)}")
    println("Upload these to the GitHub release $tag (names unchanged), then commit the seed and the manifest:")
    for ((_, f) in archived) println("  ${f.path}")
}

private fun verifySeed(layout: SeedLayout, opts: Map<String, String>) {
    val manifest = Json.parseToJsonElement(layout.manifest.readText()).jsonObject
    val inputsDir = opts["inputs"]?.takeIf { it.isNotBlank() }?.let(::File) ?: layout.inputs
    val download = manifest["archiveDownload"]!!.jsonPrimitive.content
    val inputs = manifest["inputs"]!!.jsonArray.map { it.jsonObject }
    val byRole = HashMap<String, File>()
    for (input in inputs) {
        val role = input["role"]!!.jsonPrimitive.content
        val name = input["file"]!!.jsonPrimitive.content
        val archivedFile = input["archived"]!!.jsonPrimitive.content.toBoolean()
        val file = if (archivedFile) File(inputsDir, name) else File(layout.root, name)
        if (!file.isFile) {
            check(archivedFile) { "missing ${file.path}" }
            println("downloading $download$name")
            download(download + name, file.apply { parentFile.mkdirs() })
        }
        val expected = input["sha256"]!!.jsonPrimitive.content
        val actual = sha256(file)
        check(actual == expected) { "${file.path}: sha256 $actual, manifest says $expected" }
        check(file.length() == input["size"]!!.jsonPrimitive.long) { "${file.path}: size differs from the manifest" }
        println("input  ok  $role ${file.name} $actual")
        byRole[role] = file
    }

    val out = File(layout.root, "build/seed-verify").apply { deleteRecursively() }
    runPipeline(byRole.getValue("osm"), byRole.getValue("gtfs"), byRole.getValue("bacs"), out)

    val failures = mutableListOf<String>()
    for (output in manifest["outputs"]!!.jsonArray.map { it.jsonObject }) {
        val path = output["file"]!!.jsonPrimitive.content
        val expected = output["sha256"]!!.jsonPrimitive.content
        val rebuilt = sha256(File(out, path.substringAfterLast('/')))
        val committed = File(layout.root, path).takeIf { it.isFile }?.let(::sha256)
        val ok = rebuilt == expected && committed == expected
        println("output ${if (ok) "ok " else "BAD"} $path rebuilt $rebuilt, committed $committed, manifest $expected")
        if (!ok) failures += path
    }
    check(failures.isEmpty()) { "seed not reproduced: $failures" }
    println("seed verified: every output rebuilt byte for byte from the archived inputs")
}

/** [file], copied into [dir] unless it is already there (the inputs to archive, gathered in one place). */
private fun gather(file: File, dir: File): File {
    require(file.isFile) { "no such file: ${file.path}" }
    val target = File(dir, file.name)
    if (file.canonicalFile != target.canonicalFile) file.copyTo(target, overwrite = true)
    return target
}

private fun download(url: String, to: File): File {
    val tmp = File(to.path + ".part")
    URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    check(tmp.renameTo(to)) { "cannot write ${to.path}" }
    return to
}

private fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private val PRETTY = Json { prettyPrint = true }

private data class Feed(val fileId: String, val url: String, val validFrom: String, val validUntil: String)

private const val RECORDS_URL =
    "https://data.nantesmetropole.fr/api/explore/v2.1/catalog/datasets/" +
        "244400404_transports_commun_naolib_nantes_metropole_gtfs/records?limit=20"

/** The current GTFS publication (the download URL is resolved, never hard-coded - SOURCES.md §4.1). */
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
