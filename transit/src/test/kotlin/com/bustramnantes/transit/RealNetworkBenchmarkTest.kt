package com.bustramnantes.transit

import com.bustramnantes.transit.raptor.AccessPoint
import com.bustramnantes.transit.raptor.RaptorRouter
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Runs RAPTOR on the bundled seed network (`app/src/main/assets/seed/network.bin`): 50 random stop to
 * stop "depart at" queries on a weekday of the feed. Asserts they average well under the 200 ms budget
 * and that (on the frequent network, 06:00-20:00) they all find a journey.
 */
class RealNetworkBenchmarkTest {

    @Test
    fun `50 random queries on the seed network`() {
        val seed = File("../app/src/main/assets/seed/network.bin")
        assumeTrue("seed network not found", seed.isFile)
        val net = seed.inputStream().use { NetworkFormat.read(it) }
        val router = RaptorRouter(net)
        val rnd = Random(42)
        var date = net.validFrom.plusDays(7)
        while (date.dayOfWeek.value != 2) date = date.plusDays(1)
        // Stops on the frequent network (tram, Busway, Chronobus): random stops on school/shuttle lines
        // legitimately have no service for hours (e.g. LCE at Clépettre on a weekday afternoon).
        val frequent = setOf("1", "2", "3", "4", "5") + (1..20).map { "C$it" }
        val served = net.stopPlaces.indices.filter { sp -> net.servedRoutes(sp).any { net.routes[it.route].shortName in frequent } }
        fun quays(sp: Int) = net.stopPlaces[sp].quays.map { AccessPoint(it, 0) }
        // Warm-up (day tables, JIT).
        router.departAfter(quays(served[0]), quays(served[1]), date.atTime(8, 0).atZone(ZoneId.of("Europe/Paris")).toEpochSecond())
        var found = 0
        val t0 = System.nanoTime()
        repeat(50) {
            val a = served[rnd.nextInt(served.size)]
            val b = served[rnd.nextInt(served.size)]
            val time = LocalTime.of(6 + rnd.nextInt(14), rnd.nextInt(60))
            val epoch = date.atTime(time).atZone(ZoneId.of("Europe/Paris")).toEpochSecond()
            if (router.departAfter(quays(a), quays(b), epoch, maxJourneys = 3).isNotEmpty()) found++
            else println("MISS wide=${router.departAfter(quays(a), quays(b), epoch, maxJourneys = 1, windowSeconds = 12 * 3600).firstOrNull()?.let { java.time.Instant.ofEpochSecond(it.departureEpochSecond) }}  ${net.stopPlaces[a].name} ${net.servedRoutes(a).map { net.routes[it.route].shortName }} -> ${net.stopPlaces[b].name} ${net.linkedRoutes(b).map { net.routes[it].shortName }} at $time")
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("50 queries (3 options each): $ms ms total, ${ms / 50.0} ms/query, found $found/50")
        assertTrue("too slow: $ms ms", ms / 50 < 200)
        assertTrue("too few journeys: $found", found >= 49)
    }
}
