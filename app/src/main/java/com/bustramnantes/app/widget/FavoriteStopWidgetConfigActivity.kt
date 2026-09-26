package com.bustramnantes.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.FavoriteType
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.presentation.common.components.EmptyState
import com.bustramnantes.app.presentation.common.components.LineBadge
import com.bustramnantes.app.presentation.common.components.LineBadgeSize
import com.bustramnantes.app.presentation.common.theme.BusEtTramTheme
import com.bustramnantes.app.worker.BackgroundWorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * `APPWIDGET_CONFIGURE` activity for [FavoriteStopWidget].
 *
 * Glance widgets need to know *which* favorite (at STOP_LINE_DIRECTION granularity - "one line +
 * one direction per widget, for clarity") a given home-screen instance shows.
 * Rather than a hand-rolled DataStore keyed by app-widget-id, this writes the chosen
 * [Favorite.id] into Glance's own per-instance [PreferencesGlanceStateDefinition] store (see
 * [FavoriteStopWidget] for why), then triggers a redraw of that one instance.
 *
 * This is a normal `@AndroidEntryPoint` `ComponentActivity` (not a `GlanceAppWidget`), so it can
 * inject [FavoriteRepository] directly through Hilt's regular Activity injection - no
 * `EntryPointAccessors` needed here, unlike [FavoriteStopWidget]/`WidgetRefreshWorker`.
 */
@AndroidEntryPoint
class FavoriteStopWidgetConfigActivity : ComponentActivity() {

    @Inject
    lateinit var favoriteRepository: FavoriteRepository

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Per Android widget-configuration convention: if the activity is closed without
        // explicitly setting RESULT_OK, the host removes the widget it was just about to place.
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            BusEtTramTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FavoriteStopWidgetConfigScreen(
                        favoritesFlow = favoriteRepository.observeFavorites(),
                        onFavoriteSelected = ::onFavoriteSelected,
                    )
                }
            }
        }
    }

    private fun onFavoriteSelected(favorite: Favorite) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@FavoriteStopWidgetConfigActivity).getGlanceIdBy(appWidgetId)

            updateAppWidgetState(
                this@FavoriteStopWidgetConfigActivity,
                PreferencesGlanceStateDefinition,
                glanceId,
            ) { prefs ->
                prefs.toMutablePreferences().apply { this[FavoriteStopWidget.FAVORITE_ID_KEY] = favorite.id }
            }
            FavoriteStopWidget().update(this@FavoriteStopWidgetConfigActivity, glanceId)

            // Make sure the ~5-minute refresh chain is alive now that at least one widget
            // instance exists (idempotent - see BackgroundWorkScheduler.schedule's KEEP policy).
            BackgroundWorkScheduler.schedule(this@FavoriteStopWidgetConfigActivity)

            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}

@Composable
private fun FavoriteStopWidgetConfigScreen(
    favoritesFlow: Flow<List<Favorite>>,
    onFavoriteSelected: (Favorite) -> Unit,
) {
    val favorites by favoritesFlow.collectAsState(initial = null)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Choisir un favori pour le widget",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.padding(top = 8.dp))

        // `when (val current = favorites)` (rather than a subject-less `when` over derived
        // booleans) so the compiler smart-casts `current`/`eligible` to non-null inside the
        // branches below - `favorites` itself is `by`-delegated `State` and never smart-casts.
        when (val current = favorites) {
            null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                val eligible = current.filter { it.type == FavoriteType.STOP_LINE_DIRECTION }
                if (eligible.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Star,
                        message = "Ajoutez d'abord un favori avec une ligne et une direction précises " +
                            "(depuis la fiche d'un arrêt) pour pouvoir créer ce widget.",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn {
                        items(eligible, key = { it.id }) { favorite ->
                            FavoriteConfigRow(favorite = favorite, onClick = { onFavoriteSelected(favorite) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reuses the same [LineBadge] every other screen renders line badges with, instead of a hand-
 * rolled circle - fixes both the TRAM-vs-BUS shape mismatch (a hand-rolled circle can't distinguish
 * them) and clipping of longer line short-names (a fixed-size `Box` can't grow). [Favorite] carries
 * no transport-mode field, so this always renders the BUS (circular) shape - a reasonable default
 * since most lines are bus, and still strictly better than the previous always-circular hand-roll.
 */
@Composable
private fun FavoriteConfigRow(favorite: Favorite, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LineBadge(
            // Blank strings fail `parseHexColor` on purpose, so a null color falls through to
            // `LineBadge`'s own `colorScheme.primary`/`onPrimary` default rather than a hardcoded one.
            shortName = favorite.lineShortName.orEmpty(),
            backgroundColorHex = favorite.lineColor.orEmpty(),
            textColorHex = favorite.lineTextColor.orEmpty(),
            mode = TransportMode.BUS,
            size = LineBadgeSize.Medium,
        )
        Column {
            Text(text = favorite.stopName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${favorite.lineShortName.orEmpty()} → ${(favorite.directionName ?: favorite.direction).orEmpty()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
