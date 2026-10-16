package com.bustramnantes.transit.realtime

/** A time range an alert applies to (epoch seconds; `null` = open-ended on that side). */
data class AlertPeriod(val start: Long?, val end: Long?) {
    fun contains(epochSecond: Long): Boolean = (start == null || epochSecond >= start) && (end == null || epochSecond <= end)
    fun overlaps(from: Long, to: Long): Boolean = (start == null || to >= start) && (end == null || from <= end)
}

/**
 * What an alert targets (GTFS-RT `EntitySelector`). On Naolib only [routeId] (`FR_NAOLIB:Line:<short>`)
 * and [stopId] (`FR_NAOLIB:Quay:<n>`) are ever set: route alone = the whole line, stop alone = every
 * line at that quay, both = that line at that quay.
 */
data class InformedEntity(val routeId: String?, val stopId: String?)

/**
 * One GTFS-RT service alert. [description] is HTML as published (paragraphs, lists, links to
 * diversion maps). [cause]/[effect]/[severity] are the raw GTFS-RT enum numbers (null when unset).
 */
data class ServiceAlert(
    val id: String,
    val header: String,
    val description: String,
    val url: String?,
    val periods: List<AlertPeriod>,
    val entities: List<InformedEntity>,
    val cause: Int?,
    val effect: Int?,
    val severity: Int?,
) {
    /** No title and no description: a bare targeting record (see [ServiceAlerts.clean]). */
    val isBlank: Boolean get() = header.isBlank() && description.isBlank()
    val routeIds: Set<String> get() = entities.mapNotNullTo(HashSet()) { it.routeId }
}

data class AlertsFeed(val timestamp: Long, val alerts: List<ServiceAlert>)

/**
 * GTFS-RT Alerts: decoding (same hand-written protobuf reader as [GtfsRealtime]) and the clean-up the
 * Naolib feed needs before it can be shown.
 *
 * Okina endpoint (keyless; shares the GTFS-RT 1 req / 30 s budget with TripUpdates):
 * [ALERTS_URL], the whole network in one ~12 KB message. Measured (SOURCES.md §5.5):
 *  - 59 alerts, French text in `language = "unspecified"`, descriptions are HTML.
 *  - **27 are blank** (no header, empty description): targeting records published next to a titled
 *    alert with the very same active periods (e.g. "Travaux Route du Vignoble" has no entity, its
 *    blank twin targets line 28). [clean] folds them into their titled twin.
 *  - Titled alerts with no entity at all name their lines in the text ("les lignes C1, 11 et 12 sont
 *    déviées"); [clean] infers those lines from the text when a resolver is given.
 *  - `effect` is never set, `cause` is mostly UNKNOWN_CAUSE (1), CONSTRUCTION (10) or DEMONSTRATION
 *    (5), `severity_level` mostly INFO (2): nothing machine-usable beyond "there is a disruption".
 *
 * Field numbers (gtfs-realtime.proto): FeedEntity{1 id, 5 alert}; Alert{1 active_period,
 * 5 informed_entity, 6 cause, 7 effect, 8 url, 10 header_text, 11 description_text, 14 severity_level};
 * TimeRange{1 start, 2 end}; EntitySelector{2 route_id, 5 stop_id}; TranslatedString{1 translation};
 * Translation{1 text, 2 language}.
 */
object ServiceAlerts {
    const val ALERTS_URL = "https://api.okina.fr/gateway/semgtfsrt/realtime/alerts/NAOLIBORG"

    fun decode(bytes: ByteArray): AlertsFeed {
        var timestamp = 0L
        val alerts = ArrayList<ServiceAlert>()
        ProtoReader(bytes, 0, bytes.size).forEachField { field, r ->
            when (field) {
                1 -> r.message().forEachField { f, h -> if (f == 3) timestamp = h.varint() else h.skip() }
                2 -> {
                    var id = ""
                    var alert: ServiceAlert? = null
                    r.message().forEachField { f, e ->
                        when (f) {
                            1 -> id = e.string()
                            5 -> alert = alert(e.message(), id)
                            else -> e.skip()
                        }
                    }
                    alert?.let(alerts::add)
                }
                else -> r.skip()
            }
        }
        return AlertsFeed(timestamp, alerts)
    }

    private fun alert(r: ProtoReader, id: String): ServiceAlert {
        val periods = ArrayList<AlertPeriod>()
        val entities = ArrayList<InformedEntity>()
        var cause: Int? = null
        var effect: Int? = null
        var severity: Int? = null
        var url: String? = null
        var header = ""
        var description = ""
        r.forEachField { field, x ->
            when (field) {
                1 -> {
                    var start: Long? = null
                    var end: Long? = null
                    x.message().forEachField { f, t ->
                        when (f) {
                            1 -> start = t.varint()
                            2 -> end = t.varint()
                            else -> t.skip()
                        }
                    }
                    periods += AlertPeriod(start?.takeIf { it > 0 }, end?.takeIf { it > 0 })
                }
                5 -> {
                    var route: String? = null
                    var stop: String? = null
                    x.message().forEachField { f, e ->
                        when (f) {
                            2 -> route = e.string()
                            5 -> stop = e.string()
                            else -> e.skip()
                        }
                    }
                    if (route != null || stop != null) entities += InformedEntity(route, stop)
                }
                6 -> cause = x.varint().toInt()
                7 -> effect = x.varint().toInt()
                8 -> url = translated(x.message()).takeIf { it.isNotBlank() }
                10 -> header = translated(x.message())
                11 -> description = translated(x.message())
                14 -> severity = x.varint().toInt()
                else -> x.skip()
            }
        }
        return ServiceAlert(id, header.trim(), description.trim(), url, periods, entities.distinct(), cause, effect, severity)
    }

    /** The French translation if tagged, else the first one (Naolib tags everything "unspecified"). */
    private fun translated(r: ProtoReader): String {
        val texts = ArrayList<Pair<String, String?>>()
        r.forEachField { field, x ->
            if (field == 1) {
                var text = ""
                var language: String? = null
                x.message().forEachField { f, t ->
                    when (f) {
                        1 -> text = t.string()
                        2 -> language = t.string()
                        else -> t.skip()
                    }
                }
                texts += text to language
            } else {
                x.skip()
            }
        }
        return (texts.firstOrNull { it.second == "fr" } ?: texts.firstOrNull())?.first.orEmpty()
    }

    /**
     * Makes the feed displayable:
     *  1. every **blank** alert is folded into a titled one - the titled alert with exactly the same
     *     periods (preferring one sharing a line when several match), else one sharing a line whose
     *     periods overlap; its entities are added to that alert. A blank alert nothing matches is
     *     dropped (there is no text to show).
     *  2. a titled alert still without any entity gets the lines its text names, through
     *     [lineIdForShortName] (`"C1"` -> `"FR_NAOLIB:Line:C1"`, null for an unknown name, which
     *     filters out false positives like years or street numbers).
     */
    fun clean(alerts: List<ServiceAlert>, lineIdForShortName: ((String) -> String?)? = null): List<ServiceAlert> {
        val titled = alerts.filterNot { it.isBlank }
        val extra = HashMap<String, MutableList<InformedEntity>>()
        for (blank in alerts.filter { it.isBlank }) {
            val target = twinOf(blank, titled) ?: continue
            extra.getOrPut(target.id) { ArrayList() } += blank.entities
        }
        return titled.map { alert ->
            val entities = (alert.entities + extra[alert.id].orEmpty()).distinct()
            val withText = if (entities.isEmpty() && lineIdForShortName != null) {
                linesNamedIn(alert.header + " " + alert.description)
                    .mapNotNull(lineIdForShortName)
                    .distinct()
                    .map { InformedEntity(it, null) }
            } else {
                entities
            }
            alert.copy(entities = withText)
        }
    }

    private fun twinOf(blank: ServiceAlert, titled: List<ServiceAlert>): ServiceAlert? {
        val periods = blank.periods.toSet()
        val routes = blank.routeIds
        val samePeriods = titled.filter { it.periods.toSet() == periods }
        if (samePeriods.size == 1) return samePeriods[0]
        if (samePeriods.size > 1) return samePeriods.firstOrNull { it.routeIds.any(routes::contains) } ?: samePeriods[0]
        if (routes.isEmpty()) return null
        return titled.firstOrNull { t ->
            t.routeIds.any(routes::contains) &&
                t.periods.any { p -> blank.periods.any { b -> p.overlaps(b.start ?: Long.MIN_VALUE, b.end ?: Long.MAX_VALUE) } }
        }
    }

    private val TAGS = Regex("<[^>]+>")
    /** A line short name: "50", "C1", "E8", "TE1", "NC", "4"; never followed by a letter ("Nuit"). */
    private const val LINE_TOKEN = """(?:[A-Z]{0,2}\d{1,3}[A-Z]?|[A-Z]{1,2}\d?)(?![\p{L}\d])"""
    private val LINES_PHRASE = Regex(
        """\b[Ll]ignes?\s+(?:scolaires?\s+)?($LINE_TOKEN)((?:\s*(?:,|et)\s*$LINE_TOKEN)*)""",
    )
    private val SEPARATORS = Regex("""\s*(?:,|\bet\b)\s*""")

    /** Line short names a French alert text names ("la ligne C7", "les lignes C1, 11 et 12"). */
    fun linesNamedIn(text: String): List<String> {
        val plain = TAGS.replace(text, " ").replace("&nbsp;", " ")
        val out = LinkedHashSet<String>()
        for (m in LINES_PHRASE.findAll(plain)) {
            out += m.groupValues[1]
            m.groupValues[2].split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }.forEach { out += it }
        }
        return out.toList()
    }
}
