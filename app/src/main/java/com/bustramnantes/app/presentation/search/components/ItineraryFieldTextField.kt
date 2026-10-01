package com.bustramnantes.app.presentation.search.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.presentation.search.ItineraryFieldUiState

/** One Départ/Arrivée field in Itinéraire mode. */
@Composable
fun ItineraryFieldTextField(
    state: ItineraryFieldUiState,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        label = { Text(state.label) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.onFocusChanged { onFocusChanged(it.isFocused) },
    )
}
