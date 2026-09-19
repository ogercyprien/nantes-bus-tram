package com.bustramnantes.app.presentation.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bustramnantes.app.domain.model.AddressResult
import com.bustramnantes.app.domain.model.RecentSearch
import com.bustramnantes.app.domain.usecase.search.StopSearchResult

/** Arrêt segment: switches between the Arrêt and Adresse sub-modes. */
@Composable
fun StopAddressModeContent(
    uiState: SearchUiState,
    onStopQueryChange: (String) -> Unit,
    onAddressQueryChange: (String) -> Unit,
    onOpenAddressSearch: () -> Unit,
    onCloseAddressSearch: () -> Unit,
    onStopSelected: (StopSearchResult) -> Unit,
    onAddressSelected: (AddressResult) -> Unit,
    onRecentSearchSelected: (RecentSearch) -> Unit,
    onDeleteRecentSearch: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState.stopAddressSubMode) {
        StopAddressSubMode.STOP -> StopSearchContent(
            query = uiState.stopQuery,
            recentSearches = uiState.recentSearches,
            favoriteResults = uiState.favoriteStopResults,
            stopResults = uiState.stopResults,
            onQueryChange = onStopQueryChange,
            onOpenAddressSearch = onOpenAddressSearch,
            onStopSelected = onStopSelected,
            onRecentSearchSelected = onRecentSearchSelected,
            onDeleteRecentSearch = onDeleteRecentSearch,
            modifier = modifier.fillMaxSize(),
        )

        StopAddressSubMode.ADDRESS -> AddressSearchContent(
            query = uiState.addressQuery,
            isSearching = uiState.isAddressSearching,
            results = uiState.addressResults,
            onQueryChange = onAddressQueryChange,
            onBack = onCloseAddressSearch,
            onAddressSelected = onAddressSelected,
            modifier = modifier.fillMaxSize(),
        )
    }
}
