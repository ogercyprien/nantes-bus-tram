package com.bustramnantes.app.presentation.stop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.Direction
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.FavoriteType

/** A distinct line (across both directions) available for the granular favorite choices below. */
private data class FavoriteLineChoice(
    val lineId: String,
    val lineShortName: String,
    val lineColor: String,
    val lineTextColor: String,
    val directions: List<Direction>,
)

private fun List<DepartureGroup>.toLineChoices(): List<FavoriteLineChoice> =
    groupBy { it.lineId }
        .map { (lineId, groups) ->
            val first = groups.first()
            FavoriteLineChoice(
                lineId = lineId,
                lineShortName = first.lineNumber,
                lineColor = first.lineColor,
                lineTextColor = first.lineTextColor,
                directions = groups.map { it.direction }.distinctBy { it.code },
            )
        }

/**
 * Granularity picker shown before saving a favorite: STOP / STOP_LINE /
 * STOP_LINE_DIRECTION. The two line-scoped options are only selectable once at least one line is
 * known (i.e. the departures list has loaded at least once) since they need a line - and for
 * STOP_LINE_DIRECTION, a direction - to attach to.
 *
 * Selecting a combination that already matches a favorite in [existingFavorites] switches the
 * confirm button to "Retirer" (the ViewModel removes it); otherwise it reads "Enregistrer" (adds).
 */
@Composable
fun FavoriteGranularityDialog(
    stopName: String,
    departureGroups: List<DepartureGroup>,
    initialLineId: String?,
    initialDirection: String?,
    existingFavorites: List<Favorite>,
    isSaving: Boolean,
    onConfirm: (StopDetailEvent.FavoriteGranularityChosen) -> Unit,
    onDismiss: () -> Unit,
) {
    val lineChoices = remember(departureGroups) { departureGroups.toLineChoices() }

    var selectedType by remember { mutableStateOf(FavoriteType.STOP) }
    var selectedLineId by remember {
        mutableStateOf(initialLineId ?: lineChoices.firstOrNull()?.lineId)
    }
    val selectedLine = lineChoices.find { it.lineId == selectedLineId }
    var selectedDirectionCode by remember {
        mutableStateOf(initialDirection ?: selectedLine?.directions?.firstOrNull()?.code)
    }

    // The favorite action is reachable before departures load, so [lineChoices] can start empty and
    // fill in on the first (or a later 30s) timetable emission. Seed sensible defaults once choices
    // arrive - but only when nothing is selected yet, so a user's own pick is never clobbered by a
    // background refresh.
    LaunchedEffect(lineChoices) {
        if (selectedLineId == null) selectedLineId = lineChoices.firstOrNull()?.lineId
    }
    LaunchedEffect(selectedLine) {
        if (selectedDirectionCode == null) {
            selectedDirectionCode = selectedLine?.directions?.firstOrNull()?.code
        }
    }

    fun matches(lineId: String?, direction: String?): Favorite? =
        existingFavorites.find { it.lineId == lineId && it.direction == direction }

    val currentLineId = if (selectedType == FavoriteType.STOP) null else selectedLine?.lineId
    val currentDirection = if (selectedType == FavoriteType.STOP_LINE_DIRECTION) selectedDirectionCode else null
    val currentDirectionName = if (selectedType == FavoriteType.STOP_LINE_DIRECTION) {
        selectedLine?.directions?.firstOrNull { it.code == selectedDirectionCode }?.name
    } else {
        null
    }
    val existingMatch = matches(currentLineId, currentDirection)
    val canConfirm = selectedType == FavoriteType.STOP ||
        (selectedLine != null && (selectedType == FavoriteType.STOP_LINE || selectedDirectionCode != null))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Favori pour $stopName") },
        text = {
            Column(Modifier.selectableGroup()) {
                FavoriteTypeRow(
                    label = "Cet arrêt",
                    description = "Toutes lignes et directions confondues",
                    selected = selectedType == FavoriteType.STOP,
                    enabled = true,
                    onClick = { selectedType = FavoriteType.STOP },
                )
                FavoriteTypeRow(
                    label = "Une ligne à cet arrêt",
                    description = "Les deux directions de la ligne choisie",
                    selected = selectedType == FavoriteType.STOP_LINE,
                    enabled = lineChoices.isNotEmpty(),
                    onClick = { selectedType = FavoriteType.STOP_LINE },
                )
                FavoriteTypeRow(
                    label = "Une ligne et une direction",
                    description = "Seulement la direction choisie",
                    selected = selectedType == FavoriteType.STOP_LINE_DIRECTION,
                    enabled = lineChoices.isNotEmpty(),
                    onClick = { selectedType = FavoriteType.STOP_LINE_DIRECTION },
                )

                if (selectedType != FavoriteType.STOP && lineChoices.isNotEmpty()) {
                    Text("Ligne", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    LazyRow {
                        items(lineChoices) { line ->
                            FilterChip(
                                selected = line.lineId == selectedLineId,
                                onClick = {
                                    selectedLineId = line.lineId
                                    selectedDirectionCode = line.directions.firstOrNull()?.code
                                },
                                label = { Text(line.lineShortName) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                }

                if (selectedType == FavoriteType.STOP_LINE_DIRECTION && selectedLine != null) {
                    Text("Direction", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    Column {
                        selectedLine.directions.forEach { direction ->
                            FavoriteTypeRow(
                                label = direction.name,
                                description = null,
                                selected = direction.code == selectedDirectionCode,
                                enabled = true,
                                onClick = { selectedDirectionCode = direction.code },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm && !isSaving,
                onClick = {
                    val line = selectedLine
                    onConfirm(
                        StopDetailEvent.FavoriteGranularityChosen(
                            type = selectedType,
                            lineId = currentLineId,
                            lineShortName = if (selectedType == FavoriteType.STOP) null else line?.lineShortName,
                            lineColor = if (selectedType == FavoriteType.STOP) null else line?.lineColor,
                            lineTextColor = if (selectedType == FavoriteType.STOP) null else line?.lineTextColor,
                            direction = currentDirection,
                            directionName = currentDirectionName,
                        ),
                    )
                },
            ) {
                Text(if (existingMatch != null) "Retirer" else "Enregistrer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun FavoriteTypeRow(
    label: String,
    description: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
