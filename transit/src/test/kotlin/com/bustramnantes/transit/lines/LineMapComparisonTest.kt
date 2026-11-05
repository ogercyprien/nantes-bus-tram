package com.bustramnantes.transit.lines

import com.bustramnantes.transit.NetworkFormat
import com.bustramnantes.transit.TransitNetwork
import com.bustramnantes.transit.ridePath
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.pow

/**
 * Dev tool, opt-in: renders the official raster line tiles ("before", a reference only: the app no
 * longer uses them) next to our own [LineMap] ("after") over the OSM basemap, as PNGs in
 * `$LINEMAP_OUT`. This is how every rule of the builder was checked (see `README.md` in the main
 * source set's `lines` package). Images are not committed.
 *
 * ```
 * LINEMAP_OUT=/abs/dir ./gradlew :transit:test --tests '*LineMapComparisonTest*' --rerun
 * ```
 * - `LINEMAP_ONLY=08,11`: only the views whose name starts with one of these.
 * - `LINEMAP_RANDOM=100`: the 100-view regression sweep instead of the fixed views. The views are
 *   read from `$LINEMAP_OUT/views.tsv`, else from the committed `lines/random-views.tsv` (the sweep
 *   reviewed on 2026-11-05), else drawn at random (`LINEMAP_SEED`) and saved there.
 * - `LINEMAP_DEBUG=1`: also `debug/<view>.png` (segment centrelines, junctions, each segment's lines)
 *   and `debug/<view>-raw.png` (the builder's input: every pattern's OSM ride path).
 *
 * Tiles are cached in `transit/build/linemap-tiles`. Same scale as a phone: 2 px per dp.
 */
class LineMapComparisonTest {

    data class View(val name: String, val lat: Double, val lon: Double, val zoom: Double, val w: Int = 520, val h: Int = 620)

    private val views = listOf(
        View("01-network-z12", 47.2200, -1.5550, 12.0, 700, 760),
        View("02-centre-z13", 47.2150, -1.5560, 13.0),
        View("03-centre-z14", 47.2140, -1.5560, 14.0),
        View("04-commerce-z15", 47.2131, -1.5592, 15.0),
        View("05-commerce-z16", 47.2131, -1.5592, 16.0),
        View("06-gare-nord-z15", 47.2172, -1.5440, 15.0),
        View("07-hotel-dieu-z15", 47.2110, -1.5520, 15.0),
        View("08-pirmil-z14", 47.1975, -1.5420, 14.0),
        View("09-haluchere-z14", 47.2470, -1.5210, 14.0),
        View("10-ile-de-nantes-z15", 47.2065, -1.5610, 15.0),
        View("11-reze-z14", 47.1880, -1.5500, 14.0),
        View("12-orvault-z13", 47.2500, -1.5900, 13.0),
        View("13-bretagne-z16", 47.2160, -1.5565, 16.0),
        View("14-pirmil-z16", 47.1950, -1.5445, 16.0),
        View("15-toutes-joies-z15", 47.2208, -1.5715, 15.0),
    )

    private val scale = 2.0

    @Test
    fun render() {
        val out = System.getenv("LINEMAP_OUT")
        assumeTrue("set LINEMAP_OUT", out != null)
        System.setProperty("java.awt.headless", "true")
        val outDir = File(out!!).apply { mkdirs() }
        val net = File("../app/src/main/assets/seed/network.bin").inputStream().use { NetworkFormat.read(it) }
        val routes = MapRoute.of(net)
        val maps = HashMap<Int, LineMap>()
        fun mapFor(zoom: Double): LineMap {
            val layout = floor(zoom).toInt().coerceIn(LineMapSet.LAYOUT_ZOOMS.first, LineMapSet.LAYOUT_ZOOMS.last)
            return maps.getOrPut(layout) { println("== build z$layout"); LineMapBuilder(net, layout, routes).build { println("  $it") } }
        }
        val only = System.getenv("LINEMAP_ONLY")?.split(',')?.map { it.trim() }
        val random = System.getenv("LINEMAP_RANDOM")?.toInt()
        val sweep = if (random == null) views else sweepViews(net, outDir, random) { mapFor(14.0) }
        for (v in sweep) {
            if (only != null && only.none { v.name.startsWith(it) }) continue
            val before = canvas(v)
            val after = canvas(v)
            drawBasemap(before, v); drawBasemap(after, v)
            drawOfficial(before, v)
            drawDots(before, v, net.stopPlaces.map { WebMercator.x(it.lon) to WebMercator.y(it.lat) })
            val map = mapFor(v.zoom)
            val r = Renderer(map, net, v.zoom, scale, WebMercator.x(v.lon), WebMercator.y(v.lat), v.w * scale, v.h * scale)
            r.draw(after.createGraphics().also(::hq))
            r.drawMarkers(after.createGraphics().also(::hq))
            val gap = 12
            val img = BufferedImage(before.width * 2 + gap, before.height + 44, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics(); hq(g)
            g.color = Color.WHITE; g.fillRect(0, 0, img.width, img.height)
            g.drawImage(before, 0, 44, null); g.drawImage(after, before.width + gap, 44, null)
            g.color = Color.BLACK
            runCatching {
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 24)
                g.drawString("BEFORE - official raster + official stop positions  (${v.name})", 10, 30)
                g.drawString("AFTER - our line map, open data only", before.width + gap + 10, 30)
            }
            ImageIO.write(img, "png", File(outDir, "${v.name}.png"))
            if (System.getenv("LINEMAP_DEBUG") != null) {
                val debugDir = File(outDir, "debug").apply { mkdirs() }
                val dbg = canvas(v); drawBasemap(dbg, v)
                r.draw(dbg.createGraphics().also(::hq)); r.drawDebug(dbg.createGraphics().also(::hq))
                ImageIO.write(dbg, "png", File(debugDir, "${v.name}.png"))
                val raw = canvas(v); drawBasemap(raw, v)
                r.drawRaw(raw.createGraphics().also(::hq))
                ImageIO.write(raw, "png", File(debugDir, "${v.name}-raw.png"))
            }
            println("wrote ${v.name}")
        }
    }

    /** The sweep's views: saved ones, else the committed reviewed list, else random ones near drawn stops. */
    private fun sweepViews(net: TransitNetwork, outDir: File, count: Int, z14: () -> LineMap): List<View> {
        val saved = File(outDir, "views.tsv")
        val text = when {
            saved.isFile -> saved.readText()
            else -> javaClass.getResource("/lines/random-views.tsv")?.readText()
        }
        if (text != null) return text.lines().filter { it.isNotBlank() }.take(count).map { l ->
            val (n, la, lo, z) = l.split('\t'); View(n, la.toDouble(), lo.toDouble(), z.toDouble())
        }
        val rnd = java.util.Random((System.getenv("LINEMAP_SEED") ?: "1").toLong())
        val drawn = z14().stops.filter { !it.hub }.mapNotNull { net.stopPlaceIndex(it.stopId) }.distinct()
        val zooms = listOf(12.0, 13.0, 13.0, 14.0, 14.0, 14.0, 15.0, 15.0, 15.0, 16.0, 16.0)
        return List(count) { i ->
            val sp = net.stopPlaces[drawn[rnd.nextInt(drawn.size)]]
            val z = zooms[rnd.nextInt(zooms.size)]
            // Off the stop by up to a third of the view, so stops aren't always dead centre.
            val m = WebMercator.unitsPerDp(z) * 520 / 3
            val x = WebMercator.x(sp.lon) + (rnd.nextDouble() * 2 - 1) * m; val y = WebMercator.y(sp.lat) + (rnd.nextDouble() * 2 - 1) * m
            val slug = java.text.Normalizer.normalize(sp.name, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
                .replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase()
            View("r%03d-%s-z%d".format(i + 1, slug, z.toInt()), WebMercator.lat(y), WebMercator.lon(x), z)
        }.also { list -> saved.writeText(list.joinToString("\n") { "${it.name}\t${it.lat}\t${it.lon}\t${it.zoom}" } + "\n") }
    }

    private fun canvas(v: View) = BufferedImage((v.w * scale).toInt(), (v.h * scale).toInt(), BufferedImage.TYPE_INT_RGB).also {
        val g = it.createGraphics(); g.color = Color(0xEF, 0xE9, 0xDD); g.fillRect(0, 0, it.width, it.height)
    }

    private fun hq(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    /** Draws tiles of [tileZoom] fetched by [fetch], at the view's scale. */
    private fun drawTiles(img: BufferedImage, v: View, tileZoom: Int, fetch: (Int, Int, Int) -> BufferedImage?) {
        val g = img.createGraphics(); hq(g)
        val worldPx = 512.0 * 2.0.pow(v.zoom) * scale
        val cx = (v.lon + 180) / 360 * worldPx
        val latR = Math.toRadians(v.lat)
        val cy = (1 - kotlin.math.ln(kotlin.math.tan(latR) + 1 / kotlin.math.cos(latR)) / Math.PI) / 2 * worldPx
        val n = 1 shl tileZoom
        val tilePx = worldPx / n
        val x0 = cx - img.width / 2; val y0 = cy - img.height / 2
        for (tx in floor(x0 / tilePx).toInt()..floor((x0 + img.width) / tilePx).toInt())
            for (ty in floor(y0 / tilePx).toInt()..floor((y0 + img.height) / tilePx).toInt()) {
                val tile = fetch(tileZoom, tx, ty) ?: continue
                val px = tx * tilePx - x0; val py = ty * tilePx - y0
                g.drawImage(tile, px.toInt(), py.toInt(), (px + tilePx).toInt() - px.toInt(), (py + tilePx).toInt() - py.toInt(), null)
            }
    }

    private val cacheDir = File("build/linemap-tiles").apply { mkdirs() }

    private fun curl(url: String, file: File): File? {
        if (!file.isFile) {
            file.parentFile.mkdirs()
            val p = ProcessBuilder("curl", "-sf", "-m", "30", "-A", "BusEtTramNantes-linemap-comparison/1.0", "-o", file.path, url).inheritIO().start()
            if (p.waitFor() != 0) return null
        }
        return file
    }

    private fun drawBasemap(img: BufferedImage, v: View) = drawTiles(img, v, v.zoom.toInt() + 1) { z, x, y ->
        curl("https://tile.openstreetmap.org/$z/$x/$y.png", File(cacheDir, "osm/$z/$x/$y.png"))?.let(ImageIO::read)?.let(::fade)
    }

    /** OSM basemap paled a little, as transit maps do, so the lines read first. */
    private fun fade(src: BufferedImage): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val c = src.getRGB(x, y)
            fun ch(s: Int) = (((c shr s) and 0xff) * 0.55 + 255 * 0.45).toInt()
            out.setRGB(x, y, (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
        }
        return out
    }

    /** The official tiles, the reference we compare against (fetched once, cached). */
    private fun drawOfficial(img: BufferedImage, v: View) = drawTiles(img, v, v.zoom.toInt()) { z, x, y ->
        curl("https://plan-app.naolib.fr/data/raster/v5/$z/$x/$y.png", File(cacheDir, "official/$z/$x/$y.png"))?.let(ImageIO::read)
    }

    private fun drawDots(img: BufferedImage, v: View, points: List<Pair<Double, Double>>) {
        val g = img.createGraphics(); hq(g)
        val r = LineStyle.interp(v.zoom, LaneGeometry.MARKER_RADIUS) * scale
        val upp = WebMercator.unitsPerDp(v.zoom) / scale
        val cx = WebMercator.x(v.lon); val cy = WebMercator.y(v.lat)
        g.stroke = BasicStroke((LineStyle.interp(v.zoom, LaneGeometry.MARKER_STROKE) * scale).toFloat())
        for ((mx, my) in points) {
            if (mx.isNaN()) continue
            val x = (mx - cx) / upp + img.width / 2; val y = (cy - my) / upp + img.height / 2
            if (x < -r || y < -r || x > img.width + r || y > img.height + r) continue
            val e = Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r)
            g.color = Color.WHITE; g.fill(e)
            g.color = MARKER_BLUE; g.draw(e)
        }
    }

    /** Strokes [LaneGeometry] with Java2D the way the app's MapLibre layers do. */
    private inner class Renderer(
        private val map: LineMap,
        private val net: TransitNetwork,
        private val zoom: Double,
        private val scale: Double,
        private val cx: Double,
        private val cy: Double,
        private val wPx: Double,
        private val hPx: Double,
    ) {
        private val style = map.style
        private val upd = WebMercator.unitsPerDp(zoom)
        private fun sx(x: Double) = (x - cx) / upd * scale + wPx / 2
        private fun sy(y: Double) = (cy - y) / upd * scale + hPx / 2
        private fun width(route: Int) = style.width(route, zoom)
        private val margin = 300 * upd
        private val geometry = LaneGeometry.compute(
            map, zoom,
            doubleArrayOf(cx - wPx / 2 / scale * upd - margin, cy - hPx / 2 / scale * upd - margin, cx + wPx / 2 / scale * upd + margin, cy + hPx / 2 / scale * upd + margin),
        )

        private fun path(x: DoubleArray, y: DoubleArray) = Path2D.Double().apply {
            moveTo(sx(x[0]), sy(y[0])); for (i in 1 until x.size) lineTo(sx(x[i]), sy(y[i]))
        }

        private fun round(dp: Double) = BasicStroke((dp * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        /** Casing under everything, then by rank (bus, major, tram) and line by line: edge, body, stripe. */
        fun draw(g: Graphics2D) {
            val pieces = geometry.pieces.sortedBy { style.rank(it.route) }
            val gap = style.gap(zoom)
            g.color = Color.WHITE
            for (p in pieces) { g.stroke = round(width(p.route) + gap); g.draw(path(p.x, p.y)) }
            for ((_, layer) in pieces.groupBy { style.rank(it.route) }) for ((r, list) in layer.groupBy { it.route }.toSortedMap()) {
                val paths = list.map { path(it.x, it.y) }
                if (style.chronobus[r]) { g.color = Color(LineStyle.CHRONOBUS_EDGE); g.stroke = round(width(r)); paths.forEach(g::draw) }
                g.color = Color(Integer.parseInt(map.routes[r].color.ifBlank { "888888" }, 16))
                g.stroke = when {
                    style.kind[r] == LineStyle.Kind.BOAT -> BasicStroke(
                        (width(r) * scale).toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f,
                        floatArrayOf(6f * scale.toFloat(), 4f * scale.toFloat()), 0f,
                    )
                    style.chronobus[r] -> round(width(r) * (1 - 2 * LineStyle.CHRONOBUS_EDGE_SHARE))
                    else -> round(width(r))
                }
                paths.forEach(g::draw)
                if (style.chronobus[r]) { g.color = Color.WHITE; g.stroke = round(width(r) * LineStyle.CHRONOBUS_STRIPE_SHARE); paths.forEach(g::draw) }
            }
        }

        /** Stop dots, and station dashes from [LineMap.HUB_DASH_MIN_ZOOM] (white, blue ring). */
        fun drawMarkers(g: Graphics2D) {
            val dashStops = geometry.dashes.map { it.stopId }.toSet()
            val sw = LineStyle.interp(zoom, LaneGeometry.MARKER_STROKE) * scale
            for (d in geometry.dashes) {
                val line = Line2D.Double(sx(d.x0), sy(d.y0), sx(d.x1), sy(d.y1))
                g.color = MARKER_BLUE; g.stroke = BasicStroke((d.thicknessDp * scale + 2 * sw).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(line)
                g.color = Color.WHITE; g.stroke = BasicStroke((d.thicknessDp * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(line)
            }
            val r = LineStyle.interp(zoom, LaneGeometry.MARKER_RADIUS) * scale
            g.stroke = BasicStroke(sw.toFloat())
            for (m in map.stops) {
                if (m.hub && m.stopId in dashStops) continue
                val x = sx(m.x); val y = sy(m.y)
                if (x < -r || y < -r || x > wPx + r || y > hPx + r) continue
                val e = Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r)
                g.color = Color.WHITE; g.fill(e)
                g.color = MARKER_BLUE; g.draw(e)
            }
        }

        /** Every shown pattern's ride path (the builder's input), 2 dp, in its line colour, labelled. */
        fun drawRaw(g: Graphics2D) {
            g.stroke = BasicStroke((2 * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (p in net.patterns) {
                if (style.kind[p.route] == LineStyle.Kind.HIDDEN) continue
                val pts = net.ridePath(p, 0, p.quays.size - 1)
                val x = DoubleArray(pts.size) { WebMercator.x(pts[it][1]) }; val y = DoubleArray(pts.size) { WebMercator.y(pts[it][0]) }
                g.color = Color(Integer.parseInt(net.routes[p.route].color.ifBlank { "888888" }, 16))
                g.draw(path(x, y))
            }
            g.color = Color.BLACK
            runCatching { g.font = Font(Font.SANS_SERIF, Font.PLAIN, 16) }
            for (p in net.patterns) {
                if (style.kind[p.route] == LineStyle.Kind.HIDDEN) continue
                for (q in p.quays.indices step 3) {
                    val qi = p.quays[q]
                    runCatching { g.drawString(net.routes[p.route].shortName, sx(WebMercator.x(net.quayLon[qi])).toFloat() + 3, sy(WebMercator.y(net.quayLat[qi])).toFloat() - 3) }
                }
            }
        }

        /** Segment centrelines (black) and junction nodes (red), with the lines of each segment. */
        fun drawDebug(g: Graphics2D) {
            g.stroke = BasicStroke(1.5f)
            for (s in map.segments) {
                g.color = Color.BLACK; g.draw(path(s.x, s.y))
                val m = s.x.size / 2
                runCatching {
                    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 16)
                    g.drawString(s.order.joinToString(",") { map.routes[it].shortName }, sx(s.x[m]).toFloat() + 4, sy(s.y[m]).toFloat())
                }
            }
            g.color = Color.RED
            for (s in map.segments) for (n in listOf(s.first, s.last)) g.fill(Ellipse2D.Double(sx(map.nodeX[n]) - 5, sy(map.nodeY[n]) - 5, 10.0, 10.0))
        }
    }

    private companion object {
        /** The app's stop marker ring colour. */
        val MARKER_BLUE = Color(0x15, 0x65, 0xC0)
    }
}
