package com.bustramnantes.app.widget

import android.content.Context
import android.content.Intent
import com.bustramnantes.app.MainActivity

/**
 * Contract for the Intent extras a [FavoriteStopWidget] tap uses to deep-link into
 * [MainActivity] and open the Stop Detail sheet.
 *
 * **Integration note for the MainActivity/navigation agent**: `MainActivity` must read these
 * extras from both `onCreate`'s `intent` (cold start) and `onNewIntent` (warm start, since the
 * launched Intent here does not carry `FLAG_ACTIVITY_SINGLE_TOP`... it does carry
 * `FLAG_ACTIVITY_NEW_TASK` only) and, once the nav graph/NavController exists, call:
 *
 * ```
 * navController.navigate(
 *     Screen.StopDetail.createRoute(
 *         stopId = intent.getStringExtra(WidgetDeepLink.EXTRA_STOP_ID)!!,
 *         lineId = intent.getStringExtra(WidgetDeepLink.EXTRA_LINE_ID),
 *         direction = intent.getStringExtra(WidgetDeepLink.EXTRA_DIRECTION),
 *     )
 * )
 * ```
 *
 * Presence of [EXTRA_STOP_ID] is the signal that the launching Intent came from a widget tap and
 * should be consumed as a deep link (as opposed to a plain launcher relaunch).
 */
object WidgetDeepLink {

    /** Required. [com.bustramnantes.app.domain.model.Favorite.stopId] (a GTFS StopPlace id). */
    const val EXTRA_STOP_ID = "com.bustramnantes.app.widget.EXTRA_STOP_ID"

    /** Optional. [com.bustramnantes.app.domain.model.Favorite.lineId], to pre-focus a line. */
    const val EXTRA_LINE_ID = "com.bustramnantes.app.widget.EXTRA_LINE_ID"

    /** Optional. [com.bustramnantes.app.domain.model.Favorite.direction] (a [com.bustramnantes.app.domain.model.Direction.code]). */
    const val EXTRA_DIRECTION = "com.bustramnantes.app.widget.EXTRA_DIRECTION"

    /** Custom action so this Intent is unambiguous even if extras are ever proguarded/stripped. */
    const val ACTION_VIEW_STOP_DETAIL = "com.bustramnantes.app.widget.action.VIEW_STOP_DETAIL"

    /** Builds the tap target Intent for a [FavoriteStopWidget] instance bound to [stopId]/[lineId]/[direction]. */
    fun createStopDetailIntent(
        context: Context,
        stopId: String,
        lineId: String?,
        direction: String?,
    ): Intent = Intent(ACTION_VIEW_STOP_DETAIL, null, context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(EXTRA_STOP_ID, stopId)
        .apply {
            lineId?.let { putExtra(EXTRA_LINE_ID, it) }
            direction?.let { putExtra(EXTRA_DIRECTION, it) }
        }
}
