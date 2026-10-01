package com.bustramnantes.app.presentation.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.AddressResult
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.presentation.common.navigation.SearchPrefillTarget
import com.bustramnantes.app.presentation.search.components.DepartureTimePickerDialog
import com.bustramnantes.app.presentation.search.components.ItineraryFieldTextField
import com.bustramnantes.app.presentation.search.components.ItinerarySuggestionsList
import kotlinx.datetime.LocalDateTime

/**
 * Itinéraire segment: Départ/Arrivée fields, swap button, travel-time chooser (maintenant /
 * partir à / arriver à), and the "Chercher" CTA that hands off to the itinerary_results route.
 */
@Composable
fun ItineraryModeContent(
    uiState: SearchUiState,
    onQueryChange: (SearchPrefillTarget, String) -> Unit,
    onFocusChanged: (SearchPrefillTarget, Boolean) -> Unit,
    onStopSelected: (SearchPrefillTarget, Stop) -> Unit,
    onAddressSelected: (SearchPrefillTarget, AddressResult) -> Unit,
    onSwap: () -> Unit,
    onSearch: () -> Unit,
    onOpenDepartureTimePicker: () -> Unit,
    onDismissDepartureTimePicker: () -> Unit,
    onDepartureTimeSelected: (DepartureTimeMode, LocalDateTime?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Picking a suggestion resolves the field, so the keyboard has no more purpose here. Dismiss it
    // (and drop focus) on selection: otherwise it lingers open over the now-bottom-anchored
    // "Chercher" button, hiding it. (The button correctly rises above the keyboard *while* typing;
    // this only handles the post-selection state.)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ItineraryFieldTextField(
                    state = uiState.departField,
                    onQueryChange = { onQueryChange(SearchPrefillTarget.DEPART, it) },
                    onFocusChanged = { onFocusChanged(SearchPrefillTarget.DEPART, it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                ItineraryFieldTextField(
                    state = uiState.arriveField,
                    onQueryChange = { onQueryChange(SearchPrefillTarget.ARRIVE, it) },
                    onFocusChanged = { onFocusChanged(SearchPrefillTarget.ARRIVE, it) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(onClick = onSwap) {
                Icon(Icons.Default.SwapVert, contentDescription = "Inverser départ et arrivée")
            }
        }

        // A scheduled mode with no instant can't happen through the picker (it always confirms one),
        // but fall back to the "maintenant" wording rather than showing a half-empty sentence.
        val departureSummary = when (uiState.departureTimeMode) {
            DepartureTimeMode.NOW -> "Partir maintenant"
            DepartureTimeMode.DEPART_AT -> uiState.departureDateTime?.let {
                "Partir le ${it.dayOfMonth.pad2()}/${it.monthNumber.pad2()} à ${it.hour.pad2()}:${it.minute.pad2()}"
            } ?: "Partir maintenant"

            DepartureTimeMode.ARRIVE_BY -> uiState.departureDateTime?.let {
                "Arriver à ${it.hour.pad2()}:${it.minute.pad2()} le ${it.dayOfMonth.pad2()}/${it.monthNumber.pad2()}"
            } ?: "Partir maintenant"
        }
        Surface(
            onClick = onOpenDepartureTimePicker,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = departureSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        if (uiState.isDepartureTimePickerOpen) {
            DepartureTimePickerDialog(
                initialMode = uiState.departureTimeMode,
                initialDateTime = uiState.departureDateTime,
                onConfirm = onDepartureTimeSelected,
                onDismiss = onDismissDepartureTimePicker,
                feedEnd = uiState.scheduleFeedEnd,
            )
        }

        val activeField = uiState.activeItineraryField
        if (activeField != null) {
            val fieldState = if (activeField == SearchPrefillTarget.DEPART) uiState.departField else uiState.arriveField
            ItinerarySuggestionsList(
                fieldState = fieldState,
                onStopSelected = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    onStopSelected(activeField, it)
                },
                onAddressSelected = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    onAddressSelected(activeField, it)
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        Button(
            onClick = onSearch,
            enabled = uiState.canSearchJourneys,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Chercher")
        }
    }
}

private fun Int.pad2() = toString().padStart(2, '0')
