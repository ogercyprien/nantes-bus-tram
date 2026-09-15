package com.bustramnantes.app.presentation.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.RecentSearch
import com.bustramnantes.app.domain.usecase.search.StopSearchResult
import com.bustramnantes.app.presentation.common.components.EmptyState
import com.bustramnantes.app.presentation.search.components.RecentSearchRow
import com.bustramnantes.app.presentation.search.components.SearchTextField
import com.bustramnantes.app.presentation.search.components.SectionHeader
import com.bustramnantes.app.presentation.search.components.StopResultRow

/** How many stops to show for a blank query before the user narrows it (the full catalog is ~1200). */
private const val BLANK_QUERY_STOP_CAP = 50

/** Arrêt sub-mode content: instant local results + recent searches + favorites. */
@Composable
fun StopSearchContent(
    query: String,
    recentSearches: List<RecentSearch>,
    favoriteResults: List<StopSearchResult>,
    stopResults: List<StopSearchResult>,
    onQueryChange: (String) -> Unit,
    onOpenAddressSearch: () -> Unit,
    onStopSelected: (StopSearchResult) -> Unit,
    onRecentSearchSelected: (RecentSearch) -> Unit,
    onDeleteRecentSearch: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A long-press deletes a recent search, with no undo - easy to trigger by accident. Confirm first.
    var pendingDelete by remember { mutableStateOf<RecentSearch?>(null) }
    pendingDelete?.let { recent ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Supprimer cette recherche ?") },
            text = { Text(recent.query) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteRecentSearch(recent.id)
                    pendingDelete = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Annuler") }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        SearchTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Rechercher un arrêt",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val showRecents = query.isBlank() && recentSearches.isNotEmpty()
        val isEmpty = favoriteResults.isEmpty() && stopResults.isEmpty() && !showRecents

        if (isEmpty && query.isNotBlank()) {
            EmptyState(
                icon = Icons.Default.Search,
                message = "Aucun arrêt trouvé",
                modifier = Modifier.weight(1f),
            )
        } else {
            // On a blank query the repository returns the whole ~1200-stop catalog (proximity- or
            // alphabetically-ordered); cap what we render so it reads as a browsable list, not a data
            // dump. A typed query is already narrow, so it's shown in full.
            val cappedStops = if (query.isBlank()) stopResults.take(BLANK_QUERY_STOP_CAP) else stopResults
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (showRecents) {
                    item { SectionHeader("Recherches récentes") }
                    items(recentSearches, key = { "recent_${it.id}" }) { recent ->
                        RecentSearchRow(
                            recentSearch = recent,
                            onClick = { onRecentSearchSelected(recent) },
                            onLongClick = { pendingDelete = recent },
                        )
                    }
                }
                if (favoriteResults.isNotEmpty()) {
                    item { SectionHeader("Favoris") }
                    items(favoriteResults, key = { "fav_${it.stop.id}" }) { result ->
                        StopResultRow(result = result, onClick = { onStopSelected(result) })
                    }
                }
                if (cappedStops.isNotEmpty()) {
                    // "Arrêts proches" when the list is proximity-sorted (location known), else a
                    // neutral header. On a query, it's just the matches.
                    val header = when {
                        query.isNotBlank() -> "Résultats"
                        else -> "Tous les arrêts"
                    }
                    item { SectionHeader(header) }
                    items(cappedStops, key = { it.stop.id }) { result ->
                        StopResultRow(result = result, onClick = { onStopSelected(result) })
                    }
                }
            }
        }

        Button(
            onClick = onOpenAddressSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Icon(Icons.Default.Place, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Rechercher une adresse")
        }
    }
}
