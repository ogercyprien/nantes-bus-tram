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
import androidx.compose.material3.ButtonDefaults
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
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.FavoriteType

/** What the granularity picker is for: a new favorite, or rewriting [favorite] in place. */
sealed interface FavoritePickerMode {
    data object Add : FavoritePickerMode
    data class Edit(val favorite: Favorite) : FavoritePickerMode
}

/**
 * Granularity picker for a favorite: STOP / STOP_LINE / STOP_LINE_DIRECTION. The two line-scoped
 * options are only selectable once at least one line is known (i.e. the departures list has loaded
 * at least once) since they need a line - and for STOP_LINE_DIRECTION, a direction - to attach to.
 *
 * - [FavoritePickerMode.Add] opens on the first configuration not saved yet ([initialAddSelection]);
 *   confirming adds a favorite.
 * - [FavoritePickerMode.Edit] opens on the favorite's own configuration; confirming rewrites it in
 *   place, and a "Retirer ce favori" button removes it ([onRemove]).
 *
 * Either way the confirm button never doubles as a remove: a configuration that another favorite at
 * this stop already has disables it, with "Déjà dans vos favoris" ([duplicateOf]).
 */
@Composable
fun FavoriteGranularityDialog(
    mode: FavoritePickerMode,
    stopName: String,
    departureGroups: List<DepartureGroup>,
    initialLineId: String?,
    initialDirection: String?,
    existingFavorites: List<Favorite>,
    isSaving: Boolean,
    onConfirm: (StopDetailEvent.FavoriteGranularityChosen) -> Unit,
    onRemove: (Favorite) -> Unit,
    onDismiss: () -> Unit,
) {
    val lineChoices = remember(departureGroups) { departureGroups.toLineChoices() }
    val editing = (mode as? FavoritePickerMode.Edit)?.favorite

    val initial = remember {
        editing?.let(::selectionOf)
            ?: initialAddSelection(lineChoices, existingFavorites, initialLineId, initialDirection)
    }
    var selectedType by remember { mutableStateOf(initial.type) }
    var selectedLineId by remember { mutableStateOf(initial.lineId) }
    val selectedLine = lineChoices.find { it.lineId == selectedLineId }
    var selectedDirectionCode by remember { mutableStateOf(initial.directionCode) }

    var touched by remember { mutableStateOf(false) }

    // The favorite action is reachable before departures load, so [lineChoices] can start empty and
    // fill in on the first (or a later 30s) timetable emission. Seed sensible defaults once choices
    // arrive - but only when nothing is selected yet, so a user's own pick is never clobbered by a
    // background refresh. An untouched "Ajouter" picker re-picks its first unsaved configuration.
    LaunchedEffect(lineChoices) {
        if (editing == null && !touched && lineChoices.isNotEmpty() && selectedLineId == null) {
            val fresh = initialAddSelection(lineChoices, existingFavorites, initialLineId, initialDirection)
            selectedType = fresh.type
            selectedDirectionCode = fresh.directionCode
            selectedLineId = fresh.lineId
        }
        if (selectedLineId == null) selectedLineId = lineChoices.firstOrNull()?.lineId
    }
    LaunchedEffect(selectedLine) {
        if (selectedDirectionCode == null) {
            selectedDirectionCode = selectedLine?.directions?.firstOrNull()?.code
        }
    }

    val selection = FavoriteSelection(selectedType, selectedLine?.lineId, selectedDirectionCode)
    val currentDirectionName = selection.savedDirection?.let { code ->
        selectedLine?.directions?.firstOrNull { it.code == code }?.name
    }
    val duplicate = existingFavorites.duplicateOf(selection, exceptId = editing?.id)
    val unchanged = editing != null && selectionOf(editing).let {
        it.savedLineId == selection.savedLineId && it.savedDirection == selection.savedDirection
    }
    val complete = selectedType == FavoriteType.STOP ||
        (selectedLine != null && (selectedType == FavoriteType.STOP_LINE || selectedDirectionCode != null))
    val canConfirm = complete && duplicate == null && !unchanged && !isSaving

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing != null) "Modifier le favori" else "Nouveau favori à $stopName") },
        text = {
            Column(Modifier.selectableGroup()) {
                FavoriteTypeRow(
                    label = "Cet arrêt",
                    description = "Toutes lignes et directions confondues",
                    selected = selectedType == FavoriteType.STOP,
                    enabled = true,
                    onClick = { touched = true; selectedType = FavoriteType.STOP },
                )
                FavoriteTypeRow(
                    label = "Une ligne à cet arrêt",
                    description = "Les deux directions de la ligne choisie",
                    selected = selectedType == FavoriteType.STOP_LINE,
                    enabled = lineChoices.isNotEmpty(),
                    onClick = { touched = true; selectedType = FavoriteType.STOP_LINE },
                )
                FavoriteTypeRow(
                    label = "Une ligne et une direction",
                    description = "Seulement la direction choisie",
                    selected = selectedType == FavoriteType.STOP_LINE_DIRECTION,
                    enabled = lineChoices.isNotEmpty(),
                    onClick = { touched = true; selectedType = FavoriteType.STOP_LINE_DIRECTION },
                )

                if (selectedType != FavoriteType.STOP && lineChoices.isNotEmpty()) {
                    Text("Ligne", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    LazyRow {
                        items(lineChoices) { line ->
                            FilterChip(
                                selected = line.lineId == selectedLineId,
                                onClick = {
                                    touched = true
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
                                onClick = { touched = true; selectedDirectionCode = direction.code },
                            )
                        }
                    }
                }

                if (duplicate != null) {
                    Text(
                        text = "Déjà dans vos favoris",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                if (editing != null) {
                    TextButton(
                        onClick = { onRemove(editing) },
                        enabled = !isSaving,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Retirer ce favori")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    val line = selectedLine.takeIf { selectedType != FavoriteType.STOP }
                    onConfirm(
                        StopDetailEvent.FavoriteGranularityChosen(
                            type = selectedType,
                            lineId = selection.savedLineId,
                            lineShortName = line?.lineShortName,
                            lineColor = line?.lineColor,
                            lineTextColor = line?.lineTextColor,
                            direction = selection.savedDirection,
                            directionName = currentDirectionName,
                        ),
                    )
                },
            ) {
                Text("Enregistrer")
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
