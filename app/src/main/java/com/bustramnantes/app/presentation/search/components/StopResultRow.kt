package com.bustramnantes.app.presentation.search.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.usecase.search.StopSearchResult
import com.bustramnantes.app.presentation.common.components.NameWithLineBadges

/**
 * One Arrêt-mode result row: stop name + line badges, favorite star when pinned. Also used by the
 * "À proximité" tab. The stop name has layout priority over the badges ([NameWithLineBadges]); when
 * a stop serves more lines than fit, the badges auto-scroll in a clipped strip.
 */
@Composable
fun StopResultRow(
    result: StopSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (result.isFavorite) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "Favori",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // Name first, badges get the rest (marquee when they overflow) - see NameWithLineBadges.
        NameWithLineBadges(
            title = {
                Text(
                    text = result.stop.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            lines = result.lines,
            modifier = Modifier.weight(1f),
        )
    }
}
