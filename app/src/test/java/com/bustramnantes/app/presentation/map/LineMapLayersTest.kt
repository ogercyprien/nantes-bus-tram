package com.bustramnantes.app.presentation.map

import com.bustramnantes.transit.NetworkFormat
import com.bustramnantes.transit.lines.LineMapFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File

/** The bundled line map as the Plan tab's GeoJSON: what [LineMapLayers.render] hands MapLibre. */
class LineMapLayersTest {
    private val maps = File("src/main/assets/seed/line_map.bin").inputStream().use(LineMapFormat::read)

    @Test
    fun `the seed line map belongs to the seed network`() {
        val network = File("src/main/assets/seed/network.bin").inputStream().use(NetworkFormat::read)
        assertEquals(network.feedId, maps.feedId)
        assertEquals((12..15).toList(), maps.layouts.keys.sorted())
    }

    @Test
    fun `lanes carry their line, labels only on lanes, widths scale from zoom 0`() {
        val zoom = 14.5
        val rendered = LineMapLayers.render(maps.layoutFor(zoom), zoom)
        val features = rendered.lines.features()!!
        assertTrue(features.size > 1000)
        for (f in features) {
            assertTrue(f.geometry() is LineString)
            val w0 = f.getNumberProperty("w0").toDouble()
            // A bus at z14.5 is a few dp wide: w0 * 2^14.5 is that width again.
            assertTrue(w0 * Math.pow(2.0, zoom) in 1.0..20.0)
            assertTrue(f.getStringProperty("c").startsWith("#"))
        }
        assertTrue(features.any { it.hasProperty(LineMapLayers.PROP_LABEL) })
        assertTrue(features.any { !it.hasProperty(LineMapLayers.PROP_LABEL) }) // junction curves
    }

    @Test
    fun `stations are dashes from z14, dots below`() {
        val below = LineMapLayers.render(maps.layoutFor(13.5), 13.5).markers.features()!!
        val above = LineMapLayers.render(maps.layoutFor(14.0), 14.0).markers.features()!!
        assertTrue(below.all { it.geometry() is Point })
        assertTrue(above.count { it.geometry() is LineString } >= 20)
        assertTrue((below + above).all { it.getStringProperty(LineMapLayers.PROP_STOP).startsWith("FR_NAOLIB:StopPlace:") })
    }
}
