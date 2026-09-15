package com.bustramnantes.app.presentation.map.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.presentation.common.components.EmptyState
import com.bustramnantes.app.presentation.common.components.NameWithLineBadges
import com.bustramnantes.app.presentation.map.StopSearchResult

/**
 * Search bar overlaid at the top of [com.bustramnantes.app.presentation.map.MapScreen], reusing
 * the stop-search behavior (favorites first, line badges per result) - implemented
 * with plain Material3 primitives (Card/TextField/LazyColumn) rather than the newer
 * `androidx.compose.material3.SearchBar` composable, whose expanded-vs-docked API shape has
 * changed across recent Material3 releases; this keeps the map module resilient to exactly which
 * Material3 patch version the BOM resolves to.
 */
@Composable
fun MapSearchBar(
    query: String,
    isExpanded: Boolean,
    results: List<StopSearchResult>,
    isSearching: Boolean,
    showNoResults: Boolean,
    onQueryChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onResultSelected: (Stop) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Card(
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                )
                TextField(
                    value = query,
                    onValueChange = {
                        onQueryChange(it)
                        if (!isExpanded) onExpandedChange(true)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onExpandedChange(true) },
                    placeholder = { Text("Rechercher un arrêt") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                if (query.isNotEmpty() || isExpanded) {
                    IconButton(onClick = {
                        onQueryChange("")
                        onExpandedChange(false)
                    }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Fermer la recherche")
                    }
                }
            }
        }

        if (isExpanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .heightIn(max = 360.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                when {
                    isSearching -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                    showNoResults -> EmptyState(
                        icon = Icons.Default.SearchOff,
                        message = "Aucun arrêt trouvé",
                        modifier = Modifier.height(160.dp),
                    )
                    else -> LazyColumn {
                        items(results, key = { it.stop.id }) { result ->
                            StopResultRow(result = result, onClick = { onResultSelected(result.stop) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mirrors [com.bustramnantes.app.presentation.search.components.StopResultRow]'s look exactly
 * (same spacing/icon sizing) so a stop result reads identically whether it was found from the Map
 * tab's search bar or the Search tab - kept as a separate composable rather than importing that
 * one directly since it binds to a different (structurally identical) `StopSearchResult` type
 * local to [com.bustramnantes.app.presentation.map.MapUiState]. Shares the name-first badge layout
 * ([NameWithLineBadges]): all lines are shown, auto-scrolling when they overflow.
 */
@Composable
private fun StopResultRow(
    result: StopSearchResult,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (result.isFavorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favori",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            NameWithLineBadges(
                title = {
                    Text(
                        text = result.stop.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                lines = result.lines,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
