package com.bustramnantes.app.presentation.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.AddressResult
import com.bustramnantes.app.presentation.common.components.EmptyState
import com.bustramnantes.app.presentation.search.components.AddressResultRow
import com.bustramnantes.app.presentation.search.components.SearchTextField

/** Adresse sub-mode content: 300ms-debounced geopf.fr results. */
@Composable
fun AddressSearchContent(
    query: String,
    isSearching: Boolean,
    results: List<AddressResult>,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onAddressSelected: (AddressResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            SearchTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Rechercher une adresse",
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            )
        }

        when {
            isSearching -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }

            query.isNotBlank() && results.isEmpty() -> EmptyState(
                icon = Icons.Default.Place,
                message = "Aucune adresse trouvée",
                modifier = Modifier.weight(1f),
            )

            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(results, key = { "${it.label}_${it.lat}_${it.lon}" }) { address ->
                    AddressResultRow(address = address, onClick = { onAddressSelected(address) })
                }
            }
        }
    }
}
