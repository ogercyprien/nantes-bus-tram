package com.bustramnantes.app.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bustramnantes.app.presentation.common.components.OfflineAwareContent
import com.bustramnantes.app.presentation.common.theme.ThemeMode

/**
 * Settings screen: Thème / Cache / Favoris / À propos.
 *
 * Pushed onto the back stack from Home's top-right icon - `onNavigateBack`
 * pops it, matching Home's own decoupled-callback style rather than depending on
 * `presentation.common.navigation.Screen` directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Debug conversion report (debug builds), shown in a dialog so it can be read/copied.
    var debugReport by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.CacheCleared -> snackbarHostState.showSnackbar("Cache effacé et mis à jour")
                SettingsEffect.CacheClearFailed ->
                    snackbarHostState.showSnackbar("Cache effacé, mais le rechargement a échoué")
                SettingsEffect.FavoritesCleared -> snackbarHostState.showSnackbar("Favoris supprimés")
                is SettingsEffect.DebugReport -> debugReport = effect.text
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        // Offline banner on every screen.
        OfflineAwareContent(modifier = Modifier.padding(paddingValues)) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                ThemeSection(
                    selected = uiState.themeMode,
                    dynamicColorAvailable = uiState.dynamicColorAvailable,
                    onThemeModeSelected = { viewModel.onEvent(SettingsEvent.ThemeModeSelected(it)) },
                )
                HorizontalDivider()
                CacheSection(
                    stopsCacheTimestamp = uiState.stopsCacheTimestamp,
                    linesCacheTimestamp = uiState.linesCacheTimestamp,
                    isClearing = uiState.isClearingCache,
                    onClearClicked = { viewModel.onEvent(SettingsEvent.ClearCacheClicked) },
                )
                HorizontalDivider()
                FavoritesSection(
                    favoritesCount = uiState.favoritesCount,
                    onClearClicked = { viewModel.onEvent(SettingsEvent.ClearFavoritesClicked) },
                )
                HorizontalDivider()
                AboutSection(appVersionName = uiState.appVersionName)
                if (viewModel.isDebugBuild) {
                    HorizontalDivider()
                    DebugSection(
                        isConverting = uiState.isDebugConverting,
                        onConvertClicked = { viewModel.onEvent(SettingsEvent.DebugConvertGtfsClicked) },
                        onRebuildLineMapClicked = { viewModel.onEvent(SettingsEvent.DebugRebuildLineMapClicked) },
                    )
                }
            }
        }
    }

    debugReport?.let { report ->
        AlertDialog(
            onDismissRequest = { debugReport = null },
            title = { Text("Conversion GTFS") },
            text = { Text(report) },
            confirmButton = { TextButton(onClick = { debugReport = null }) { Text("OK") } },
        )
    }

    if (uiState.showClearCacheDialog) {
        ConfirmDialog(
            title = "Effacer le cache ?",
            text = "Les horaires téléchargés seront supprimés (l'application revient aux données " +
                "intégrées) puis retéléchargés immédiatement.",
            confirmLabel = "Effacer",
            onConfirm = { viewModel.onEvent(SettingsEvent.ClearCacheConfirmed) },
            onDismiss = { viewModel.onEvent(SettingsEvent.ClearCacheDismissed) },
        )
    }

    if (uiState.showClearFavoritesDialog) {
        ConfirmDialog(
            title = "Supprimer tous les favoris ?",
            text = "Cette action est irréversible : tous vos arrêts favoris seront supprimés.",
            confirmLabel = "Tout supprimer",
            onConfirm = { viewModel.onEvent(SettingsEvent.ClearFavoritesConfirmed) },
            onDismiss = { viewModel.onEvent(SettingsEvent.ClearFavoritesDismissed) },
        )
    }
}

/**
 * Preference-category label ("Thème"/"Cache"/"Favoris"/"À propos"). Deliberately a
 * distinct look from `search/components/SectionHeader` (which mutes a transient list caption like
 * "Recherches récentes") - these are permanent page-section titles and use the app's primary color
 * to read as a settings-category header rather than a caption.
 */
@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ThemeSection(
    selected: ThemeMode,
    dynamicColorAvailable: Boolean,
    onThemeModeSelected: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader("Thème")
        Column(Modifier.selectableGroup()) {
            ThemeModeRow(
                label = "Système",
                selected = selected == ThemeMode.SYSTEM,
                onClick = { onThemeModeSelected(ThemeMode.SYSTEM) },
            )
            ThemeModeRow(
                label = "Clair",
                selected = selected == ThemeMode.LIGHT,
                onClick = { onThemeModeSelected(ThemeMode.LIGHT) },
            )
            ThemeModeRow(
                label = "Sombre",
                selected = selected == ThemeMode.DARK,
                onClick = { onThemeModeSelected(ThemeMode.DARK) },
            )
        }
        Text(
            text = if (dynamicColorAvailable) {
                "La couleur dynamique Material You est appliquée automatiquement d'après votre fond d'écran."
            } else {
                "La couleur dynamique Material You nécessite Android 12 ou une version plus récente."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ThemeModeRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun CacheSection(
    stopsCacheTimestamp: Long?,
    linesCacheTimestamp: Long?,
    isClearing: Boolean,
    onClearClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader("Cache")
        // Stops, lines and timetables all come from one GTFS network (bundled, then refreshed daily),
        // so both timestamps are the same; the line one is kept for the repository contract.
        Text(
            text = "Arrêts, lignes et horaires : " +
                (stopsCacheTimestamp ?: linesCacheTimestamp)?.let(::formatCacheAge).orEmpty()
                    .ifEmpty { "données intégrées à l'application" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        OutlinedButton(
            onClick = onClearClicked,
            enabled = !isClearing,
            modifier = Modifier.padding(16.dp),
        ) {
            if (isClearing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Effacer")
        }
    }
}

@Composable
private fun FavoritesSection(
    favoritesCount: Int,
    onClearClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader("Favoris")
        Button(
            onClick = onClearClicked,
            enabled = favoritesCount > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text("Tout supprimer")
        }
    }
}

@Composable
private fun AboutSection(appVersionName: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SectionHeader("À propos")
        Text(
            text = if (appVersionName.isNotBlank()) "Version $appVersionName" else "Version inconnue",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        Text(
            text = "Données : Nantes Métropole – Naolib (GTFS, SIRI, perturbations GTFS-RT) et Département de " +
                "Loire-Atlantique (bacs de Loire), Licence Ouverte ; " +
                "adresses : geopf.fr ; fond de carte, cheminements piétons et tracé des lignes " +
                "(dessiné par l'application à partir du GTFS) : © contributeurs OpenStreetMap (ODbL) ; " +
                "police Noto Sans (SIL Open Font License).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * Debug builds only: converts the current open-data GTFS on this phone and
 * reports the time and peak heap - the go/no-go for on-device conversion (vs a hosted packed file).
 * Same for the line map's layouts, rebuilt on the phone after each GTFS refresh.
 */
@Composable
private fun DebugSection(
    isConverting: Boolean,
    onConvertClicked: () -> Unit,
    onRebuildLineMapClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader("Débogage")
        OutlinedButton(onClick = onConvertClicked, enabled = !isConverting, modifier = Modifier.padding(16.dp)) {
            if (isConverting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Convertir le GTFS sur l'appareil")
        }
        OutlinedButton(onClick = onRebuildLineMapClicked, enabled = !isConverting, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Recalculer le tracé des lignes")
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

/**
 * Human-readable cache age ("shows cache age"), e.g. "il y a 3 min", or "jamais mis
 * à jour" when [timestampMs] is `null` (cache empty/never populated).
 */
private fun formatCacheAge(timestampMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
    if (timestampMs == null) return "jamais mis à jour"
    val minutes = ((nowMs - timestampMs).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        minutes < 24 * 60 -> "il y a ${minutes / 60} h"
        else -> "il y a ${minutes / (24 * 60)} j"
    }
}
