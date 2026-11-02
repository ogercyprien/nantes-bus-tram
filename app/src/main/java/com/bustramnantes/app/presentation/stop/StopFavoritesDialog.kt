package com.bustramnantes.app.presentation.stop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.presentation.common.components.LineBadge
import com.bustramnantes.app.presentation.common.components.LineBadgeSize

/**
 * "Favoris à <arrêt>": the star action once the stop has at least one favorite. Two parts:
 * - "Gérer les favoris à cet arrêt" - one row per saved favorite (badge + what it follows) with
 *   Modifier (opens [FavoriteGranularityDialog] in edit mode) and Retirer (immediate, undoable from
 *   the snackbar);
 * - "Ajouter un favori à cet arrêt" - the same picker as for a stop without favorites.
 */
@Composable
fun StopFavoritesDialog(
    stopName: String,
    favorites: List<Favorite>,
    departureGroups: List<DepartureGroup>,
    onEdit: (Favorite) -> Unit,
    onRemove: (Favorite) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val modesByLineId = remember(departureGroups) {
        departureGroups.toLineChoices().associate { it.lineId to it.transportMode }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Favoris à $stopName") },
        text = {
            Column {
                Text(
                    text = "Gérer les favoris à cet arrêt",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                favorites.forEach { favorite ->
                    FavoriteRow(
                        favorite = favorite,
                        mode = favorite.lineId?.let(modesByLineId::get) ?: TransportMode.BUS,
                        onEdit = { onEdit(favorite) },
                        onRemove = { onRemove(favorite) },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAdd)
                        .padding(vertical = 12.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Ajouter un favori à cet arrêt", color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        },
    )
}

@Composable
private fun FavoriteRow(
    favorite: Favorite,
    mode: TransportMode,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val label = favoriteScopeLabel(favorite)
    val spoken = favorite.lineShortName?.let { "Ligne $it, $label" } ?: label
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
    ) {
        val shortName = favorite.lineShortName
        val color = favorite.lineColor
        val textColor = favorite.lineTextColor
        if (shortName != null && color != null && textColor != null) {
            LineBadge(
                shortName = shortName,
                backgroundColorHex = color,
                textColorHex = textColor,
                mode = mode,
                size = LineBadgeSize.Small,
            )
        } else {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Modifier : $spoken")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = "Retirer : $spoken")
        }
    }
}
