package com.bustramnantes.app.presentation.itinerary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.presentation.common.components.DisruptionAmber
import com.bustramnantes.app.presentation.common.components.LineBadgeWithAlert
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.presentation.common.components.LineBadge
import com.bustramnantes.app.presentation.common.components.LineBadgeSize

/**
 * One row in the itinerary results list: total duration + departure/arrival
 * times, a mode-sequence row (`🚶 → C2 → 🚶`), the transfer count and, when the journey involves
 * any walking, the total walking time (see [formatTotalWalkingTime]). Tapping opens the
 * step-by-step detail view via [onClick].
 */
@Composable
fun JourneySummaryCard(
    journey: Journey,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    legDisruptions: Map<JourneySection.Transit, List<Disruption>> = emptyMap(),
) {
    val disrupted = journey.sections.filterIsInstance<JourneySection.Transit>().filter { legDisruptions[it].orEmpty().isNotEmpty() }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatClockTime(journey.departureTime)} → ${formatClockTime(journey.arrivalTime)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = formatJourneyDuration(journey.totalDurationSeconds),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            ModeSequenceRow(sections = journey.sections, disruptedLegs = disrupted.toSet())
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatTransferCount(journey.transferCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            formatTotalWalkingTime(journey.sections)?.let { walkingText ->
                Text(
                    text = walkingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Disruption notices on the lines/stops this journey uses while it runs (details in the
            // journey view).
            disrupted.firstOrNull()?.let { leg ->
                val count = disrupted.flatMap { legDisruptions[it].orEmpty() }.distinct().size
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = DisruptionAmber, modifier = Modifier.size(16.dp))
                    Text(
                        text = if (count == 1) {
                            "Perturbation : ${legDisruptions.getValue(leg).first().title}"
                        } else {
                            "$count perturbations sur ce trajet"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

/** `🚶 → C2 → 🚶` style mode sequence, scrollable so a long chain never clips. */
@Composable
fun ModeSequenceRow(
    sections: List<JourneySection>,
    modifier: Modifier = Modifier,
    disruptedLegs: Set<JourneySection.Transit> = emptySet(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sections.forEachIndexed { index, section ->
            when (section) {
                is JourneySection.Walking -> Icon(
                    imageVector = Icons.Filled.DirectionsWalk,
                    contentDescription = "Marche, ${section.distanceMeters} mètres",
                    modifier = Modifier.padding(2.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is JourneySection.Transit -> LineBadgeWithAlert(
                    shortName = section.lineNumber,
                    backgroundColorHex = section.lineColor,
                    textColorHex = section.lineTextColor,
                    mode = section.transportMode,
                    hasDisruption = section in disruptedLegs,
                    size = LineBadgeSize.Small,
                    modifier = Modifier.semantics {
                        contentDescription = "Ligne ${section.lineNumber} direction ${section.directionName}"
                    },
                )
            }
            if (index != sections.lastIndex) {
                Text(
                    text = "→",
                    modifier = Modifier.padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
