package com.bustramnantes.app.presentation.search.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bustramnantes.app.presentation.search.SearchTopMode

/** "Arrêt" / "Itinéraire" segmented control below the search bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchModeSegmentedControl(
    selected: SearchTopMode,
    onSelected: (SearchTopMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        SegmentedButton(
            selected = selected == SearchTopMode.STOP,
            onClick = { onSelected(SearchTopMode.STOP) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) {
            Text("Arrêt")
        }
        SegmentedButton(
            selected = selected == SearchTopMode.ITINERARY,
            onClick = { onSelected(SearchTopMode.ITINERARY) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) {
            Text("Itinéraire")
        }
    }
}
