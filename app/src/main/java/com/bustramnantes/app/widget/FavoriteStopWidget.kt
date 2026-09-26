package com.bustramnantes.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontStyle
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import com.bustramnantes.app.R
import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.presentation.common.components.ensureAccessibleTextColor
import com.bustramnantes.app.presentation.common.components.parseHexColor
import com.bustramnantes.app.presentation.common.utils.END_OF_SERVICE_LABEL
import com.bustramnantes.app.presentation.common.utils.PHANTOM_DEPARTURE_LABEL
import com.bustramnantes.app.presentation.common.utils.formatDepartureTime
import dagger.hilt.android.EntryPointAccessors

/**
 * One Glance widget instance per configured favorite: stop name, a line-badge
 * equivalent for the favorite's line, and its next 3 departures in `"3 min (17:43)"` format.
 *
 * Per-instance configuration (*which* favorite this particular home-screen instance shows) is
 * stored via Glance's own [PreferencesGlanceStateDefinition] rather than a hand-rolled DataStore
 * keyed by app-widget-id: Glance already persists one [androidx.datastore.preferences.core.Preferences]
 * blob per widget instance and automatically deletes it when that instance is removed from the
 * home screen, which is exactly the "per-widget-id -> favoriteId mapping" this widget needs. See
 * [FavoriteStopWidgetConfigActivity] for how [FAVORITE_ID_KEY] gets written.
 *
 * Fetching departures is a live/uncached call, so it happens here in
 * [provideGlance] itself (invoked whenever this instance is asked to redraw, whether from
 * [FavoriteStopWidgetConfigActivity], [com.bustramnantes.app.worker.WidgetRefreshWorker], or the
 * platform) rather than being pre-computed and merely displayed.
 */
class FavoriteStopWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val favoriteId = prefs[FAVORITE_ID_KEY]

        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetHiltEntryPoint::class.java)
        val favorite = favoriteId?.let { entryPoint.favoriteRepository().getFavoriteById(it) }

        var departures: List<Departure> = emptyList()
        var isError = false
        if (favorite != null) {
            val result = entryPoint.getFavoritesWithDeparturesUseCase()(listOf(favorite)).first()
            departures = result.departures
            isError = result.isError
        }

        provideContent {
            FavoriteStopWidgetContent(context = context, favorite = favorite, departures = departures, isError = isError)
        }
    }

    companion object {
        val FAVORITE_ID_KEY = stringPreferencesKey("favorite_id")
    }
}

@Composable
private fun FavoriteStopWidgetContent(
    context: Context,
    favorite: Favorite?,
    departures: List<Departure>,
    isError: Boolean,
) {
    GlanceTheme {
        var boxModifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(12.dp)
        if (favorite != null) {
            val tapIntent = WidgetDeepLink.createStopDetailIntent(
                context = context,
                stopId = favorite.stopId,
                lineId = favorite.lineId,
                direction = favorite.direction,
            )
            boxModifier = boxModifier.clickable(actionStartActivity(tapIntent))
        }

        Box(modifier = boxModifier) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                if (favorite == null) {
                    Text(
                        text = WIDGET_NOT_CONFIGURED_LABEL,
                        style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onBackground),
                    )
                    Text(
                        text = WIDGET_TAP_TO_CONFIGURE_LABEL,
                        style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 13.sp),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Mode comes from the departures (the line's GTFS route_type) so tram lines
                        // get the tram badge shape; bus is the safe default when there are none.
                        LineBadgeBox(favorite, departures.firstOrNull()?.transportMode ?: TransportMode.BUS)
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        Text(
                            text = favorite.stopName,
                            maxLines = 1,
                            style = TextStyle(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = GlanceTheme.colors.onBackground,
                            ),
                        )
                    }
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    when {
                        isError -> Text(
                            text = WIDGET_OFFLINE_LABEL,
                            style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 14.sp),
                        )
                        departures.isEmpty() -> Text(
                            text = END_OF_SERVICE_LABEL,
                            style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 14.sp),
                        )
                        else -> departures.take(3).forEach { departure ->
                            if (departure.isSuspectedPhantom) {
                                // Glance can't style part of one Text: struck-through, greyed time +
                                // a separate un-struck label (same treatment as DepartureTimeText).
                                Row {
                                    Text(
                                        text = formatDepartureTime(departure),
                                        maxLines = 1,
                                        style = TextStyle(
                                            color = GlanceTheme.colors.outline,
                                            fontSize = 14.sp,
                                            fontStyle = FontStyle.Italic,
                                            textDecoration = TextDecoration.LineThrough,
                                        ),
                                    )
                                    Text(
                                        text = " · $PHANTOM_DEPARTURE_LABEL",
                                        maxLines = 1,
                                        style = TextStyle(
                                            color = GlanceTheme.colors.outline,
                                            fontSize = 14.sp,
                                            fontStyle = FontStyle.Italic,
                                        ),
                                    )
                                }
                            } else {
                                Text(
                                    text = formatDepartureTime(departure),
                                    maxLines = 1,
                                    style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 14.sp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Glance's limited widget composables can't reuse the Compose
 * [com.bustramnantes.app.presentation.common.components.LineBadge] directly (different
 * component/modifier types), so this is a minimal square equivalent: line color background,
 * short name text, contrast-corrected via the same [ensureAccessibleTextColor] helper the app
 * theme uses.
 */
@Composable
private fun LineBadgeBox(favorite: Favorite, mode: TransportMode) {
    val background = favorite.lineColor?.let(::parseHexColor) ?: Color(0xFFEE7402)
    val rawForeground = favorite.lineTextColor?.let(::parseHexColor) ?: Color.White
    val foreground = ensureAccessibleTextColor(background, rawForeground)

    // Match the app's LineBadge: tram = wider, slightly-rounded rectangle; bus = near-circular pill;
    // boats (Navibus, bacs) = a boat outline, drawn from a vector tinted with the line colour.
    val isTram = mode == TransportMode.TRAM
    val isBoat = mode == TransportMode.FERRY || mode == TransportMode.BAC
    val shaped = GlanceModifier.height(28.dp)
    val modifier = when {
        isBoat -> shaped.width(46.dp)
            .background(ImageProvider(R.drawable.badge_boat_hull), colorFilter = ColorFilter.tint(ColorProvider(day = background, night = background)))
            .padding(end = 10.dp, top = 2.dp)
        isTram -> shaped.width(40.dp).cornerRadius(8.dp).background(background)
        else -> shaped.width(28.dp).cornerRadius(14.dp).background(background)
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = favorite.lineShortName.orEmpty(),
            maxLines = 1,
            style = TextStyle(
                color = ColorProvider(day = foreground, night = foreground),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

// Hardcoded French UI copy, matching this codebase's existing convention (see e.g.
// END_OF_SERVICE_LABEL, OFFLINE_BANNER_TEXT) rather than strings.xml - Glance widget content
// isn't a @Composable that could use stringResource() cheaply anyway.
private const val WIDGET_NOT_CONFIGURED_LABEL = "Widget non configuré"
private const val WIDGET_TAP_TO_CONFIGURE_LABEL = "Appuyez pour choisir un favori"
private const val WIDGET_OFFLINE_LABEL = "Hors ligne"
