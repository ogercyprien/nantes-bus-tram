package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.network.NetworkIndex
import com.bustramnantes.app.domain.model.ItineraryTimePreference
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.transit.NetworkFormat
import com.bustramnantes.transit.walk.WalkGraphFormat
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.random.Random
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live, opt-in validation of the on-device planner against the official one:
 * `LIVE_API=1 ./gradlew --no-daemon :app:testDebugUnitTest --tests '*LiveLocalVsPlanApp*'`.
 *
 * [CASES] random trips between two random points (each a random stop of the seed network moved up to
 * 400 m in a random direction, i.e. street addresses) at a random departure time in the next 3 days
 * (06:00-22:00). For each: the earliest arrival among the official planner's walk/bus solutions
 * (`plan-app /api/itinerary/search`, used here only as a reference) vs the earliest arrival among the
 * local journeys (walk-only options counted up to 30 min on both sides). Reports local − official
 * arrival per case and its distribution; fails when local finds nothing where the official one finds a
 * journey, or when fewer than 80 % land within +5 min (crow-fly walking makes small differences
 * expected). Debug a case with `CASES=3,17` (prints both journeys) and `SEED=…`; add `--rerun`, the
 * env vars aren't task inputs.
 *
 * Results (2 × 40 trips, seeds 20261012 and 777): local found a journey every time the
 * official planner did; median Δ −2 / −1 min; within +5 min 39/40 and 38/40 (the rest: the official
 * route starts or ends with an 18-21 min walk from a stop beyond the local 1.5 km access radius,
 * where the local plan prefers an extra bus); local is earlier in most cases, by a few minutes, from
 * catching a bus the official planner misses by seconds (it walks streets, slower than crow-fly × 1.3).
 */
class LiveLocalVsPlanAppItineraryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val zone = ZoneId.of("Europe/Paris")
    private val planFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    @Test
    fun `local planner vs official planner on random trips`() {
        assumeTrue("set LIVE_API=1 to run", System.getenv("LIVE_API") == "1")
        val network = File("src/main/assets/seed/network.bin").inputStream().use { NetworkFormat.read(it) }
        val index = NetworkIndex(network) {
            File("src/main/assets/seed/walk_graph.bin").takeIf { it.isFile }?.inputStream()?.use(WalkGraphFormat::read)
        }
        val seed = System.getenv("SEED")?.toLongOrNull() ?: 20261012L
        val rnd = Random(seed)
        val stops = index.stops
        val today = LocalDate.now(zone)

        data class Row(val label: String, val official: Long?, val officialTransfers: Int?, val local: Long?, val localTransfers: Int?)
        val rows = ArrayList<Row>()
        val only = System.getenv("CASES")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        repeat(CASES) { i ->
            val (aLat, aLon) = jitter(stops[rnd.nextInt(stops.size)].let { it.lat to it.lon }, rnd)
            val (bLat, bLon) = jitter(stops[rnd.nextInt(stops.size)].let { it.lat to it.lon }, rnd)
            val date = today.plusDays(rnd.nextLong(0, 3))
            val time = LocalDateTime.of(date, java.time.LocalTime.of(6 + rnd.nextInt(16), rnd.nextInt(60)))
            if (only != null && i !in only) return@repeat
            val official = runCatching { officialBest(aLat, aLon, bLat, bLon, time) }.getOrNull()
            val local = planJourneys(
                index, aLat, aLon, bLat, bLon,
                ItineraryTimePreference.DepartAt(time.toKotlinLocalDateTime()),
            ).minByOrNull { it.arrivalTime }
            if (only != null) {
                println("case #$i official: ${officialDetail(aLat, aLon, bLat, bLon, time)}")
                println("case #$i local: " + local?.sections?.joinToString(" | ") { s ->
                    when (s) {
                        is JourneySection.Walking -> "walk ${s.durationSeconds / 60}min ${s.distanceMeters}m"
                        is JourneySection.Transit -> "${s.lineNumber} ${s.concernedStops.first().name} ${hm(s.departureTime.epochSeconds)} -> ${s.concernedStops.last().name} ${hm(s.arrivalTime.epochSeconds)}"
                    }
                })
            }
            rows += Row(
                label = "#$i %.4f,%.4f -> %.4f,%.4f at %s".format(aLat, aLon, bLat, bLon, time),
                official = official?.first,
                officialTransfers = official?.second,
                local = local?.arrivalTime?.epochSeconds,
                localTransfers = local?.let { j -> j.sections.count { it is JourneySection.Transit }.minus(1).coerceAtLeast(0) },
            )
            Thread.sleep(300)
        }

        val deltas = rows.mapNotNull { r -> if (r.official != null && r.local != null) (r.local - r.official) / 60 else null }
        rows.forEach { r ->
            val d = if (r.official != null && r.local != null) "${(r.local - r.official) / 60} min" else "n/a"
            println("${r.label}: local-official = $d, transfers local ${r.localTransfers} / official ${r.officialTransfers}")
        }
        val sorted = deltas.sorted()
        val within = deltas.count { it <= 5 }
        println(
            "seed $seed, ${rows.size} cases, ${deltas.size} comparable; median Δ ${sorted.getOrNull(sorted.size / 2)} min, " +
                "≤ +3 min ${deltas.count { it <= 3 }}, ≤ +5 min $within, earlier than official ${deltas.count { it < 0 }}, " +
                "worst ${sorted.lastOrNull()} min",
        )
        val localMissing = rows.count { it.official != null && it.local == null }
        println("local found nothing where the official planner did: $localMissing")
        assertTrue(localMissing == 0)
        assertTrue("only $within / ${deltas.size} within +5 min", within * 100 >= deltas.size * 80)
    }

    /** Earliest (arrival epoch s, transfers) among the official walk/bus solutions. */
    private fun officialBest(aLat: Double, aLon: Double, bLat: Double, bLon: Double, time: LocalDateTime): Pair<Long, Int>? {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val url = "https://plan-app.naolib.fr/api/itinerary/search?from=${enc("A")}&from_lat=$aLat&from_lng=$aLon" +
            "&to=${enc("B")}&to_lat=$bLat&to_lng=$bLon&type=departure&date=${time.toLocalDate()}" +
            "&time=%02d:%02d".format(time.hour, time.minute) +
            "&avoid_dis=false&access=false&avoid_walk=false&walk_speed=4&electric_bike=false"
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
        val solutions = json.parseToJsonElement(body).jsonObject["solutions"]?.jsonArray ?: return null
        return solutions.map { it.jsonObject }
            .filter { it["mode"]?.jsonPrimitive?.contentOrNull in setOf("walk", "bus") }
            .mapNotNull { s ->
                val api = s["api"] as? JsonObject ?: return@mapNotNull null
                val arrival = api["ArrivalTime"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val departure = api["DepartureTime"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val epoch = LocalDateTime.parse(arrival, planFormat).atZone(zone).toEpochSecond()
                val start = LocalDateTime.parse(departure, planFormat).atZone(zone).toEpochSecond()
                // Like the local planner, only count a walk-only option up to 30 min (the official
                // one proposes 1 h+ walks, which would otherwise read as "local is 100 min late").
                val walkOnly = s["mode"]?.jsonPrimitive?.contentOrNull == "walk"
                if (walkOnly && epoch - start > LocalItineraryRepository.WALK_ONLY_MAX_SECONDS) return@mapNotNull null
                epoch to (api["InterchangeNumber"]?.jsonPrimitive?.intOrNull ?: 0)
            }
            .minByOrNull { it.first }
    }

    private fun hm(epoch: Long) = java.time.Instant.ofEpochSecond(epoch).atZone(zone).toLocalTime().toString()

    /** The official best solution's legs, for debugging (`CASES=3,17`). */
    private fun officialDetail(aLat: Double, aLon: Double, bLat: Double, bLon: Double, time: LocalDateTime): String {
        val url = "https://plan-app.naolib.fr/api/itinerary/search?from=A&from_lat=$aLat&from_lng=$aLon" +
            "&to=B&to_lat=$bLat&to_lng=$bLon&type=departure&date=${time.toLocalDate()}" +
            "&time=%02d:%02d".format(time.hour, time.minute) +
            "&avoid_dis=false&access=false&avoid_walk=false&walk_speed=4&electric_bike=false"
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
        val solutions = json.parseToJsonElement(body).jsonObject["solutions"]?.jsonArray ?: return "none"
        return solutions.map { it.jsonObject }.filter { it["mode"]?.jsonPrimitive?.contentOrNull in setOf("walk", "bus") }
            .joinToString("\n    ") { sol ->
                val api = sol["api"]!!.jsonObject
                "${api["DepartureTime"]?.jsonPrimitive?.contentOrNull} -> ${api["ArrivalTime"]?.jsonPrimitive?.contentOrNull}: " +
                    sol["sections"]!!.jsonArray.joinToString(" | ") { sec ->
                        val o = sec.jsonObject
                        val line = (o["line"] as? JsonObject)?.get("number")?.jsonPrimitive?.contentOrNull
                        val sapi = o["api"] as? JsonObject
                        if (line != null) {
                            val ride = sapi?.get("PTRide")?.jsonObject
                            "$line ${ride?.get("Departure")?.jsonObject?.get("Time")?.jsonPrimitive?.contentOrNull?.takeLast(8)}->${ride?.get("Arrival")?.jsonObject?.get("Time")?.jsonPrimitive?.contentOrNull?.takeLast(8)}"
                        } else {
                            val leg = sapi?.get("Leg")?.jsonObject
                            "walk ${leg?.get("Departure")?.jsonObject?.get("Time")?.jsonPrimitive?.contentOrNull?.takeLast(8)}->${leg?.get("Arrival")?.jsonObject?.get("Time")?.jsonPrimitive?.contentOrNull?.takeLast(8)}"
                        }
                    }
            }
    }

    private fun jitter(p: Pair<Double, Double>, rnd: Random): Pair<Double, Double> {
        val d = rnd.nextDouble(0.0, 400.0)
        val a = rnd.nextDouble(0.0, 2 * Math.PI)
        val dLat = d * cos(a) / 111_000.0
        val dLon = d * kotlin.math.sin(a) / (111_000.0 * cos(Math.toRadians(p.first)))
        return (p.first + dLat) to (p.second + dLon)
    }

    private companion object {
        const val CASES = 40
    }
}
