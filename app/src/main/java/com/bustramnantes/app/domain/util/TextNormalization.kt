package com.bustramnantes.app.domain.util

import java.text.Normalizer

/**
 * Lowercases and strips diacritics/accents for accent-insensitive comparison and search.
 * `"Léon Jost"` and `"aéroport"` both fold to their plain-ASCII, lowercase forms, so a query typed
 * without accents (`"leon jost"`, `"aerop"`) still matches. Also collapses runs of whitespace so
 * `"Gare  Sud"` and `"Gare Sud"` compare equal.
 *
 * Uses Unicode NFD decomposition then drops the combining marks (`\p{Mn}`) - available on both the
 * JVM and Android, so it is safe in pure-Kotlin unit tests too.
 */
fun normalizeForMatch(text: String): String {
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
    val withoutMarks = decomposed.replace(COMBINING_MARKS, "")
    return withoutMarks.lowercase().replace(WHITESPACE_RUN, " ").trim()
}

private val COMBINING_MARKS = "\\p{Mn}+".toRegex()
private val WHITESPACE_RUN = "\\s+".toRegex()
