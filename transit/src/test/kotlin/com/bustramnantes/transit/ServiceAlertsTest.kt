package com.bustramnantes.transit

import com.bustramnantes.transit.realtime.AlertPeriod
import com.bustramnantes.transit.realtime.InformedEntity
import com.bustramnantes.transit.realtime.ServiceAlert
import com.bustramnantes.transit.realtime.ServiceAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceAlertsTest {

    private val feed = ServiceAlerts.decode(javaClass.getResource("/okina/alerts.pb")!!.readBytes())
    private val knownLines = setOf("C1", "C6", "C7", "11", "12", "28", "36", "79", "77", "102", "1", "2", "3", "81", "50")
    private fun lineId(short: String) = if (short in knownLines) "FR_NAOLIB:Line:$short" else null

    @Test
    fun `decodes the captured Okina feed`() {
        assertEquals(59, feed.alerts.size)
        assertTrue(feed.timestamp > 1_700_000_000)
        val works = feed.alerts.first { it.header.startsWith("Travaux quais Langlois") }
        assertEquals(setOf("FR_NAOLIB:Line:50", "FR_NAOLIB:Line:81"), works.routeIds)
        assertTrue(works.description.startsWith("<h4>"))
        assertEquals(1, works.periods.size)
        assertNotNull(works.periods[0].start)
        val bonde = feed.alerts.first { it.header.startsWith("Report de l") && "Bonde" in it.header }
        assertTrue(InformedEntity("FR_NAOLIB:Line:C6", "FR_NAOLIB:Quay:2497") in bonde.entities)
        assertEquals(27, feed.alerts.count { it.isBlank })
    }

    @Test
    fun `clean folds blank twins into their titled alert and drops the rest`() {
        val cleaned = ServiceAlerts.clean(feed.alerts, ::lineId)
        assertEquals(32, cleaned.size)
        assertTrue(cleaned.none { it.isBlank })
        // "Travaux Route du Vignoble" has no entity; its blank twin (same periods) targets line 28.
        val vignoble = cleaned.first { it.header.startsWith("Travaux Route du Vignoble") }
        assertEquals(setOf("FR_NAOLIB:Line:28"), vignoble.routeIds)
        // "Travaux boulevard de l'Estuaire" gains the line-5 quay targets of its twin.
        val estuaire = cleaned.first { it.header.startsWith("Travaux boulevard de l'Estuaire") }
        assertTrue(estuaire.entities.any { it.routeId == "FR_NAOLIB:Line:5" && it.stopId == "FR_NAOLIB:Quay:1411" })
    }

    @Test
    fun `clean infers lines from the text only for alerts without any target`() {
        val cleaned = ServiceAlerts.clean(feed.alerts, ::lineId)
        val sully = cleaned.first { it.header == "Travaux rue Sully" }
        assertEquals(setOf("FR_NAOLIB:Line:C6"), sully.routeIds)
        // Targeted alerts keep exactly their targets even if the text names other lines.
        val langlois = cleaned.first { it.header.startsWith("Travaux quais Langlois") }
        assertEquals(setOf("FR_NAOLIB:Line:50", "FR_NAOLIB:Line:81"), langlois.routeIds)
    }

    @Test
    fun `line names in French alert texts`() {
        assertEquals(listOf("C1", "11", "12"), ServiceAlerts.linesNamedIn("Travaux rue Gambetta, les lignes C1, 11 et 12 sont déviées"))
        assertEquals(listOf("127", "147"), ServiceAlerts.linesNamedIn("les lignes scolaires 127, 147 sont déviées"))
        assertEquals(listOf("30"), ServiceAlerts.linesNamedIn("les lignes 30 et Nuit Sud sont déviées"))
        assertEquals(emptyList<String>(), ServiceAlerts.linesNamedIn("les lignes de tramway, de busway"))
        assertEquals(listOf("C7"), ServiceAlerts.linesNamedIn("<p>La <strong>ligne C7</strong> est déviée</p>".replace("<strong>", "")))
    }

    @Test
    fun `blank alert with no twin and no line is dropped`() {
        val lone = ServiceAlert("x", "", "", null, listOf(AlertPeriod(1, 2)), emptyList(), null, null, null)
        val titled = ServiceAlert("y", "Titre", "", null, listOf(AlertPeriod(5, 6)), emptyList(), null, null, null)
        assertEquals(listOf("y"), ServiceAlerts.clean(listOf(lone, titled)).map { it.id })
        assertFalse(AlertPeriod(5, 6).contains(7))
        assertTrue(AlertPeriod(null, 6).contains(-100))
    }
}
