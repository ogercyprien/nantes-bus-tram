package com.bustramnantes.app.presentation.common.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.presentation.common.utils.destinationLabel

/**
 * One departure row, the same everywhere departures are listed (Stop Detail blocks, favorite
 * cards): optional line badge, [DepartureTimeText], then the `"→ …"` label from
 * [destinationLabel] - so a short run reads "→ Espace Diderot" in both places.
 *
 * @param fixedDirectionName the direction already named by the surrounding block/card, or null when
 *   the list mixes directions (see [destinationLabel]).
 * @param showLineBadge prefix the row with the departure's line badge (whole-stop favorites).
 * @param hasDisruption amber dot on that badge.
 */
@Composable
fun DepartureRow(
    departure: Departure,
    fixedDirectionName: String?,
    modifier: Modifier = Modifier,
    showLineBadge: Boolean = false,
    hasDisruption: Boolean = false,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showLineBadge) {
            LineBadgeWithAlert(
                shortName = departure.lineNumber,
                backgroundColorHex = departure.lineColor,
                textColorHex = departure.lineTextColor,
                mode = departure.transportMode,
                hasDisruption = hasDisruption,
                size = LineBadgeSize.Small,
            )
            Spacer(Modifier.width(8.dp))
        }
        DepartureTimeText(departure = departure)
        departure.destinationLabel(fixedDirectionName)?.let { destination ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = "→ $destination",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
