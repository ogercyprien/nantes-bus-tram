package com.bustramnantes.app.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Entry point the platform broadcasts `APPWIDGET_UPDATE`/`APPWIDGET_DELETED`/etc. to,
 * registered in `AndroidManifest.xml` against `res/xml/favorite_stop_widget_info.xml`.
 *
 * No per-instance cleanup is implemented here: [GlanceAppWidgetReceiver]'s default `onDeleted`
 * already forwards to [FavoriteStopWidget]'s [androidx.glance.state.PreferencesGlanceStateDefinition]
 * state store, which deletes the per-instance `favorite_id` mapping automatically when a widget
 * instance is removed from the home screen.
 */
class FavoriteStopWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavoriteStopWidget()
}
