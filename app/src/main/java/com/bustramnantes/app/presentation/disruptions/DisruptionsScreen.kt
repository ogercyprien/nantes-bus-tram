package com.bustramnantes.app.presentation.disruptions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.presentation.common.components.DisruptionDialog
import com.bustramnantes.app.presentation.common.components.DisruptionRow
import com.bustramnantes.app.presentation.common.components.EmptyState
import com.bustramnantes.app.presentation.common.components.LineBadge
import com.bustramnantes.app.presentation.common.components.LineBadgeSize

/**
 * Every disruption notice on the network (Okina GTFS-RT Alerts): "En cours", then "À venir", with a
 * row of line chips to narrow the list to one line. Tapping a notice opens its full text. Notices
 * that name no line (rare: a city-wide event) only show unfiltered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisruptionsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DisruptionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Perturbations") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (state.lineFilters.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = state.selectedLineId == null,
                            onClick = { viewModel.onEvent(DisruptionsEvent.LineFilterSelected(null)) },
                            label = { Text("Toutes") },
                        )
                        state.lineFilters.forEach { line ->
                            FilterChip(
                                selected = state.selectedLineId == line.id,
                                onClick = { viewModel.onEvent(DisruptionsEvent.LineFilterSelected(line.id)) },
                                label = { LineBadge(line, size = LineBadgeSize.Small) },
                            )
                        }
                    }
                }
            }
            if (state.isEmpty) {
                item {
                    EmptyState(
                        icon = Icons.Filled.CheckCircle,
                        message = "Aucune perturbation signalée",
                    )
                }
            }
            section("En cours", state.inForce, state) { viewModel.onEvent(DisruptionsEvent.DisruptionClicked(it)) }
            section("À venir", state.upcoming, state) { viewModel.onEvent(DisruptionsEvent.DisruptionClicked(it)) }
            item {
                Text(
                    text = "Source : Naolib / Nantes Métropole, données ouvertes (GTFS-RT).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
    state.openDisruption?.let { d ->
        DisruptionDialog(
            disruption = d,
            lines = d.lineIds.mapNotNull(state.linesById::get),
            onDismiss = { viewModel.onEvent(DisruptionsEvent.DismissDisruption) },
        )
    }
}

private fun LazyListScope.section(
    title: String,
    disruptions: List<Disruption>,
    state: DisruptionsUiState,
    onOpen: (Disruption) -> Unit,
) {
    if (disruptions.isEmpty()) return
    item {
        Text(
            text = "$title (${disruptions.size})",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
    }
    items(disruptions, key = { "$title|${it.id}" }) { d ->
        DisruptionRow(d, d.lineIds.mapNotNull(state.linesById::get), onClick = { onOpen(d) })
        HorizontalDivider()
    }
}
