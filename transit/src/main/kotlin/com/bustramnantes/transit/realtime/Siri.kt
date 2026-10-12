package com.bustramnantes.transit.realtime

import org.xmlpull.v1.XmlPullParser
import java.io.Reader
import java.time.OffsetDateTime

/**
 * One `MonitoredStopVisit` of an Okina SIRI StopMonitoring delivery - a vehicle journey's call at a
 * quay. Times are epoch seconds (null when absent). Ids are kept as sent, minus the `:LOC` suffix the
 * gateway appends (so [vehicleJourneyRef] is the GTFS `trip_id`, [lineRef] the GTFS `route_id`).
 */
data class SiriVisit(
    /** `FR_NAOLIB:Quay:<n>` - the quay this visit was requested for. */
    val monitoringRef: String,
    val lineRef: String,
    val vehicleJourneyRef: String?,
    /** `A` / `R`. */
    val directionName: String?,
    /** Quay id of the journey's last stop - per trip, so short runs carry their own. */
    val destinationRef: String?,
    val destinationName: String?,
    val aimedArrival: Long?,
    val expectedArrival: Long?,
    val aimedDeparture: Long?,
    val expectedDeparture: Long?,
    val monitored: Boolean,
) {
    val lineShortName: String get() = lineRef.removePrefix("FR_NAOLIB:Line:")

    /** The best known passage time: expected departure, else expected arrival, else aimed. */
    val bestTime: Long? get() = expectedDeparture ?: expectedArrival ?: aimedDeparture ?: aimedArrival

    /** The timetabled time. */
    val aimedTime: Long? get() = aimedDeparture ?: aimedArrival
}

/**
 * Okina's keyless SIRI StopMonitoring endpoint (verified live, see SOURCES.md §5.2):
 * `POST` raw XML to `/anshar/services?datasetId=NAOLIBORG` (the documented `/anshar/siri` path is
 * 403 without a key), one `StopMonitoringRequest` per **Quay** (a StopPlace id returns no visits).
 * Responses are gzip-compressed on request. **1 request per 30 s per IP** across all SIRI paths.
 */
object Siri {
    const val ENDPOINT = "https://api.okina.fr/gateway/sem/realtime/anshar/services?datasetId=NAOLIBORG"

    /** The request body for [quayIds] (ids XML-escaped). [timestamp] is ISO-8601 UTC. */
    fun stopMonitoringRequest(quayIds: Collection<String>, timestamp: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<Siri xmlns=\"http://www.siri.org.uk/siri\" version=\"2.0\"><ServiceRequest>")
        append("<RequestTimestamp>").append(escape(timestamp)).append("</RequestTimestamp>")
        append("<RequestorRef>opendata</RequestorRef>")
        for (q in quayIds) {
            append("<StopMonitoringRequest version=\"2.0\">")
            append("<RequestTimestamp>").append(escape(timestamp)).append("</RequestTimestamp>")
            append("<MonitoringRef>").append(escape(q)).append("</MonitoringRef>")
            append("</StopMonitoringRequest>")
        }
        append("</ServiceRequest></Siri>")
    }

    /**
     * Parses every `MonitoredStopVisit` of a ServiceDelivery. Streaming (pull parser), namespace
     * agnostic, tolerant: a visit missing its quay or line is skipped, unknown elements are ignored.
     */
    fun parseStopMonitoring(reader: Reader, parser: XmlPullParser): List<SiriVisit> {
        parser.setInput(reader)
        val visits = ArrayList<SiriVisit>()
        var inVisit = false
        val fields = HashMap<String, String>()
        val path = ArrayList<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':')
                    path += name
                    if (name == "MonitoredStopVisit") {
                        inVisit = true
                        fields.clear()
                    }
                }
                XmlPullParser.TEXT -> if (inVisit && path.isNotEmpty()) {
                    val text = parser.text.trim()
                    if (text.isNotEmpty()) fields.putIfAbsent(path.last(), text)
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfter(':')
                    if (name == "MonitoredStopVisit" && inVisit) {
                        inVisit = false
                        toVisit(fields)?.let(visits::add)
                    }
                    if (path.isNotEmpty()) path.removeAt(path.size - 1)
                }
            }
            event = parser.next()
        }
        return visits
    }

    private fun toVisit(f: Map<String, String>): SiriVisit? {
        val quay = f["MonitoringRef"] ?: f["StopPointRef"] ?: return null
        val line = f["LineRef"]?.removeSuffix(":LOC") ?: return null
        return SiriVisit(
            monitoringRef = quay.removeSuffix(":LOC"),
            lineRef = line,
            vehicleJourneyRef = f["DatedVehicleJourneyRef"]?.removeSuffix(":LOC"),
            directionName = f["DirectionName"],
            destinationRef = f["DestinationRef"]?.removeSuffix(":LOC"),
            destinationName = f["DestinationName"] ?: f["DestinationDisplay"],
            aimedArrival = f["AimedArrivalTime"]?.let(::parseTime),
            expectedArrival = f["ExpectedArrivalTime"]?.let(::parseTime),
            aimedDeparture = f["AimedDepartureTime"]?.let(::parseTime),
            expectedDeparture = f["ExpectedDepartureTime"]?.let(::parseTime),
            monitored = f["Monitored"] == "true",
        )
    }

    private fun parseTime(s: String): Long? = runCatching { OffsetDateTime.parse(s).toEpochSecond() }.getOrNull()

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
