package com.bustramnantes.app.presentation.common.utils

import com.bustramnantes.app.domain.model.Disruption
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val PARIS = TimeZone.of("Europe/Paris")
private val DAYS = listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")

/**
 * When a disruption applies, relative to [now], in a few words:
 *  - in force: "Jusqu'à 21:30" (ends today), "Jusqu'à demain 03:00", "Jusqu'au 20/11", "En cours"
 *    (no end);
 *  - upcoming: "À partir de 21:30", "À partir de demain 09:30", "À partir du mar. 13/10 à 09:30".
 * Times are Nantes local time.
 */
fun formatDisruptionWhen(disruption: Disruption, now: Instant, zone: TimeZone = PARIS): String {
    val active = disruption.periods.firstOrNull { it.contains(now) }
    if (disruption.periods.isEmpty() || active != null) {
        val end = active?.end ?: return "En cours"
        return "Jusqu'" + relative(end, now, zone, sameDay = "à %t", nextDay = "à demain %t", later = "au %d")
    }
    val start = disruption.nextStartAfter(now) ?: return "Terminé"
    return "À partir " + relative(start, now, zone, sameDay = "de %t", nextDay = "de demain %t", later = "du %w %d à %t")
}

private fun relative(t: Instant, now: Instant, zone: TimeZone, sameDay: String, nextDay: String, later: String): String {
    val local = t.toLocalDateTime(zone)
    val today = now.toLocalDateTime(zone).date
    val pattern = when (local.date.toEpochDays() - today.toEpochDays()) {
        0 -> sameDay
        1 -> nextDay
        else -> later
    }
    return pattern.replace("%t", hhmm(local)).replace("%d", ddmm(local, today.year)).replace("%w", DAYS[local.dayOfWeek.ordinal])
}

private fun hhmm(t: LocalDateTime) = "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"

private fun ddmm(t: LocalDateTime, currentYear: Int): String {
    val base = "${t.dayOfMonth.toString().padStart(2, '0')}/${t.monthNumber.toString().padStart(2, '0')}"
    return if (t.year == currentYear) base else "$base/${t.year}"
}
