package com.bustramnantes.app.presentation.search.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.AddressResult
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.presentation.search.ItineraryFieldUiState

/**
 * Inline stop + address suggestion list shown below whichever Départ/Arrivée field is currently
 * focused (each field "accepts a stop or an address"). Stop matches (instant,
 * local) are listed before address matches (debounced, remote) since both can be non-empty at once.
 */
@Composable
fun ItinerarySuggestionsList(
    fieldState: ItineraryFieldUiState,
    onStopSelected: (Stop) -> Unit,
    onAddressSelected: (AddressResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (fieldState.stopSuggestions.isEmpty() && fieldState.addressSuggestions.isEmpty() && !fieldState.isSearchingAddress) {
        return
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn {
            items(fieldState.stopSuggestions, key = { "stop_${it.id}" }) { stop ->
                ItinerarySuggestionRow(
                    icon = Icons.Default.DirectionsBus,
                    title = stop.name,
                    onClick = { onStopSelected(stop) },
                )
            }
            items(fieldState.addressSuggestions, key = { "addr_${it.label}_${it.lat}_${it.lon}" }) { address ->
                ItinerarySuggestionRow(
                    icon = Icons.Default.Place,
                    title = address.label,
                    subtitle = address.city,
                    onClick = { onAddressSelected(address) },
                )
            }
            if (fieldState.isSearchingAddress) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ItinerarySuggestionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
