package com.bustramnantes.transit

import com.bustramnantes.transit.walk.WalkGraph
import com.bustramnantes.transit.walk.WalkGraphFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/** The bundled walking graph (`app/src/main/assets/seed/walk_graph.bin`, built from OSM). */
class WalkGraphTest {

    private val graph: WalkGraph? = File("../app/src/main/assets/seed/walk_graph.bin")
        .takeIf { it.isFile }?.inputStream()?.use(WalkGraphFormat::read)

    private fun walk(lat1: Double, lon1: Double, lat2: Double, lon2: Double, limit: Double = 10_000.0): Double? {
        val g = graph!!
        val a = g.snap(lat1, lon1)!!
        val b = g.snap(lat2, lon2)!!
        return g.search(a, limit).distanceTo(b)
    }

    @Test
    fun `rivers need a bridge`() {
        assumeTrue(graph != null)
        // Quai de la Fosse -> Trentemoult: 1.2 km across the Loire, the nearest bridge (Anne de Bretagne) is ~1 km upstream.
        val crow = haversineMeters(47.2085, -1.5690, 47.1975, -1.5720)
        val walked = walk(47.2085, -1.5690, 47.1975, -1.5720)!!
        assertTrue("crow $crow walked $walked", walked > 2 * crow)
        // Same bank, a few streets: close to crow-fly × ~1.3.
        val near = walk(47.2133, -1.5560, 47.2160, -1.5500)!!
        val nearCrow = haversineMeters(47.2133, -1.5560, 47.2160, -1.5500)
        assertTrue("crow $nearCrow walked $near", near in nearCrow..nearCrow * 1.8)
    }

    @Test
    fun `outside the metropole nothing snaps`() {
        assumeTrue(graph != null)
        assertNull(graph!!.snap(47.4784, -0.5632)) // Angers
        assertNotNull(graph.snap(47.2184, -1.5536)) // Nantes centre
    }

    @Test
    fun `paths start and end at the query points and match the distance`() {
        assumeTrue(graph != null)
        val g = graph!!
        val a = g.snap(47.2133, -1.5560)!!
        val b = g.snap(47.2160, -1.5500)!!
        val search = g.search(a, 5_000.0)
        val path = search.pathTo(b)!!
        assertEquals(47.2133, path.first()[0], 1e-9)
        assertEquals(-1.5500, path.last()[1], 1e-9)
        var length = 0.0
        for (i in 1 until path.size) length += haversineMeters(path[i - 1][0], path[i - 1][1], path[i][0], path[i][1])
        assertEquals(search.distanceTo(b)!!, length, 0.05 * length + 5)
    }

    @Test
    fun `format round trip`() {
        assumeTrue(graph != null)
        val out = ByteArrayOutputStream()
        WalkGraphFormat.write(graph!!, out)
        val back = WalkGraphFormat.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(graph.vertexCount, back.vertexCount)
        assertEquals(graph.edgeCount, back.edgeCount)
        assertEquals(graph.geomLat.size, back.geomLat.size)
    }

    /** Opt-in: `WALK_PAIRS=path ./gradlew :transit:test --tests '*WalkGraphTest*' --rerun` dumps random pairs. */
    @Test
    fun `dump random pairs for an external comparison`() {
        val target = System.getenv("WALK_PAIRS") ?: return
        val g = graph!!
        val rnd = Random(7)
        val lines = ArrayList<String>()
        while (lines.size < 40) {
            val lat = 47.17 + rnd.nextDouble() * 0.10
            val lon = -1.64 + rnd.nextDouble() * 0.17
            val bearing = rnd.nextDouble() * 2 * Math.PI
            val r = 200 + rnd.nextDouble() * 1300
            val lat2 = lat + r * kotlin.math.cos(bearing) / 111_195.0
            val lon2 = lon + r * kotlin.math.sin(bearing) / (111_195.0 * kotlin.math.cos(Math.toRadians(lat)))
            val a = g.snap(lat, lon, 60.0) ?: continue
            val b = g.snap(lat2, lon2, 60.0) ?: continue
            val d = g.search(a, 8_000.0).distanceTo(b) ?: continue
            lines += "$lat,$lon,$lat2,$lon2,${haversineMeters(lat, lon, lat2, lon2).toInt()},${d.toInt()}"
        }
        File(target).writeText(lines.joinToString("\n"))
    }
}
