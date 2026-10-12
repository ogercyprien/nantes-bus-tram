package com.bustramnantes.transit.realtime

/** One stop of a GTFS-RT TripUpdate: absolute times when given, else delays (seconds). */
data class StopTimePrediction(
    val stopSequence: Int?,
    val stopId: String?,
    val arrivalTime: Long?,
    val arrivalDelay: Int?,
    val departureTime: Long?,
    val departureDelay: Int?,
)

data class TripUpdate(
    val tripId: String,
    val stopTimes: List<StopTimePrediction>,
)

/** A decoded GTFS-RT feed (TripUpdates only). [timestamp] is the header timestamp (epoch s). */
data class TripUpdatesFeed(val timestamp: Long, val updates: List<TripUpdate>)

/**
 * Minimal GTFS Realtime protobuf decoder - just the TripUpdate subset the provisional board needs,
 * so the app carries no protobuf runtime or code generator (F-Droid friendly, a few dozen lines).
 *
 * Okina endpoint (keyless, own 1 req / 30 s budget, independent of SIRI's):
 * `https://api.okina.fr/gateway/semgtfsrt/realtime/trip-updates/NAOLIBORG`, the whole network in
 * one ~380 KB message (86 KB gzipped). Coverage is incomplete (misses ~27 % of tracked vehicles,
 * SOURCES.md §5.3): a stop-gap only - absence from it means nothing.
 *
 * Field numbers from gtfs-realtime.proto: FeedMessage{1 header, 2 entity}; FeedHeader{3 timestamp};
 * FeedEntity{1 id, 3 trip_update}; TripUpdate{1 trip, 2 stop_time_update}; TripDescriptor{1 trip_id};
 * StopTimeUpdate{1 stop_sequence, 2 arrival, 3 departure, 4 stop_id}; StopTimeEvent{1 delay, 2 time}.
 */
object GtfsRealtime {
    const val TRIP_UPDATES_URL = "https://api.okina.fr/gateway/semgtfsrt/realtime/trip-updates/NAOLIBORG"

    fun decodeTripUpdates(bytes: ByteArray): TripUpdatesFeed {
        var timestamp = 0L
        val updates = ArrayList<TripUpdate>()
        ProtoReader(bytes, 0, bytes.size).forEachField { field, r ->
            when (field) {
                1 -> r.message().forEachField { f, h -> if (f == 3) timestamp = h.varint() else h.skip() }
                2 -> {
                    var update: TripUpdate? = null
                    r.message().forEachField { f, e -> if (f == 3) update = tripUpdate(e.message()) else e.skip() }
                    update?.let(updates::add)
                }
                else -> r.skip()
            }
        }
        return TripUpdatesFeed(timestamp, updates)
    }

    private fun tripUpdate(r: ProtoReader): TripUpdate? {
        var tripId: String? = null
        val stops = ArrayList<StopTimePrediction>()
        r.forEachField { field, x ->
            when (field) {
                1 -> x.message().forEachField { f, t -> if (f == 1) tripId = t.string() else t.skip() }
                2 -> stops += stopTimeUpdate(x.message())
                else -> x.skip()
            }
        }
        return tripId?.let { TripUpdate(it, stops) }
    }

    private fun stopTimeUpdate(r: ProtoReader): StopTimePrediction {
        var seq: Int? = null
        var stopId: String? = null
        var arr: Pair<Int?, Long?> = null to null
        var dep: Pair<Int?, Long?> = null to null
        r.forEachField { field, x ->
            when (field) {
                1 -> seq = x.varint().toInt()
                2 -> arr = event(x.message())
                3 -> dep = event(x.message())
                4 -> stopId = x.string()
                else -> x.skip()
            }
        }
        return StopTimePrediction(seq, stopId, arr.second, arr.first, dep.second, dep.first)
    }

    private fun event(r: ProtoReader): Pair<Int?, Long?> {
        var delay: Int? = null
        var time: Long? = null
        r.forEachField { field, x ->
            when (field) {
                1 -> delay = x.varint().toInt() // int32: negative delays are sign-extended 64-bit varints
                2 -> time = x.varint()
                else -> x.skip()
            }
        }
        return delay to time
    }
}

/** Protobuf wire-format reader over `bytes[start, end)`. */
internal class ProtoReader(private val bytes: ByteArray, private var pos: Int, private val end: Int) {
    private var wireType = 0

    inline fun forEachField(block: (field: Int, reader: ProtoReader) -> Unit) {
        while (hasMore()) {
            val field = nextTag()
            block(field, this)
        }
    }

    fun hasMore(): Boolean = pos < end

    fun nextTag(): Int {
        val tag = varint().toInt()
        wireType = tag and 7
        return tag ushr 3
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun length(): Int = varint().toInt()

    fun message(): ProtoReader {
        val len = length()
        val r = ProtoReader(bytes, pos, pos + len)
        pos += len
        return r
    }

    fun string(): String {
        val len = length()
        val s = String(bytes, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun skip() {
        when (wireType) {
            0 -> varint()
            1 -> pos += 8
            2 -> {
                // Not `pos += length()`: that reads `pos` before length() advances past the varint.
                val len = length()
                pos += len
            }
            5 -> pos += 4
            else -> throw IllegalStateException("unsupported wire type $wireType")
        }
    }
}
