package com.bustramnantes.transit.gtfs

import java.io.InputStream
import java.io.Reader

/**
 * Minimal streaming RFC-4180 CSV reader for GTFS files - deliberately tiny and dependency-free.
 *
 * Handles what the Naolib feed (and GTFS in general) actually contains: a UTF-8 BOM on the header,
 * CRLF or LF line ends, quoted fields with `""` escapes and embedded commas/newlines. It never
 * materialises the file: `stop_times.txt` is ~583 MB uncompressed (4.6 M rows), so rows are handed
 * to the caller one at a time and the same [Row] instance is reused between them.
 */
class CsvReader(private val input: Reader) {

    // Own buffer instead of BufferedReader.read(): that one is synchronized per char, which is
    // measurable over the ~600 M characters of stop_times.txt.
    private val buf = CharArray(1 shl 16)
    private var pos = 0
    private var len = 0
    private var pushedBack = -2
    private val fields = ArrayList<String>(16)
    private val sb = StringBuilder(64)
    private var first = true

    /** Column name -> index, from the header line. */
    val header: Map<String, Int>

    init {
        header = if (readRecord()) {
            fields.mapIndexed { i, name -> name.trim() to i }.toMap()
        } else {
            emptyMap()
        }
    }

    /**
     * Reads every data row, calling [onRow] with a reused [Row] view. The view is only valid during
     * the callback (copy any value you keep - Strings themselves are immutable, so `row[i]` is safe).
     */
    inline fun forEachRow(onRow: (Row) -> Unit) {
        val row = Row(this)
        while (nextRecord()) onRow(row)
    }

    @PublishedApi
    internal fun nextRecord(): Boolean {
        while (readRecord()) {
            // Skip blank lines (a single empty field) - some feeds end with one.
            if (fields.size == 1 && fields[0].isEmpty()) continue
            return true
        }
        return false
    }

    @PublishedApi
    internal fun field(index: Int): String = if (index in fields.indices) fields[index] else ""

    /** Reads one record into [fields]; `false` at end of input. */
    private fun readRecord(): Boolean {
        fields.clear()
        var c = read()
        if (first) {
            first = false
            if (c == 0xFEFF) c = read()
        }
        if (c == -1) return false
        sb.setLength(0)
        var inQuotes = false
        while (true) {
            if (inQuotes) {
                when (c) {
                    -1 -> { fields += sb.toString(); return true }
                    '"'.code -> {
                        val next = read()
                        if (next == '"'.code) {
                            sb.append('"')
                        } else {
                            inQuotes = false
                            c = next
                            continue
                        }
                    }
                    else -> sb.append(c.toChar())
                }
            } else {
                when (c) {
                    -1, '\n'.code -> { fields += sb.toString(); return true }
                    '\r'.code -> {
                        // CR, possibly followed by LF: either way the record ends here. The LF (if any)
                        // is consumed by marking/peeking.
                        val next = read()
                        if (next != '\n'.code) pushedBack = next
                        fields += sb.toString()
                        return true
                    }
                    ','.code -> { fields += sb.toString(); sb.setLength(0) }
                    '"'.code -> if (sb.isEmpty()) inQuotes = true else sb.append('"')
                    else -> sb.append(c.toChar())
                }
            }
            c = read()
        }
    }

    private fun read(): Int {
        if (pushedBack != -2) {
            val c = pushedBack
            pushedBack = -2
            return c
        }
        if (pos == len) {
            len = input.read(buf, 0, buf.size)
            pos = 0
            if (len <= 0) {
                len = 0
                return -1
            }
        }
        return buf[pos++].code
    }

    /** A view on the current record. */
    class Row(@PublishedApi internal val reader: CsvReader) {
        operator fun get(index: Int): String = reader.field(index)
    }

    companion object {
        fun of(stream: InputStream): CsvReader = CsvReader(stream.reader(Charsets.UTF_8))
    }
}

/** Column index for [name], or -1 when the file has no such column (optional GTFS fields). */
fun CsvReader.column(name: String): Int = header[name] ?: -1

/** Column index for [name]; throws when a required GTFS column is missing. */
fun CsvReader.requireColumn(name: String): Int =
    header[name] ?: throw GtfsFormatException("missing column '$name'")

class GtfsFormatException(message: String) : Exception(message)
