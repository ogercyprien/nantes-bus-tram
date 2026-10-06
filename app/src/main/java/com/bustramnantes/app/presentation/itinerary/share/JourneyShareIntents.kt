package com.bustramnantes.app.presentation.itinerary.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.CalendarContract
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android glue for the itinerary share actions. Text/content comes from the pure builders in
 * `JourneyShareText.kt`; this file only wraps it in intents.
 */
object JourneyShareIntents {

    /** Must match the `<provider android:authorities>` in the manifest. */
    private fun fileProviderAuthority(context: Context) = "${context.packageName}.fileprovider"

    /** Sub-directory of `cacheDir` exposed by `res/xml/file_paths.xml`. */
    private const val SHARED_DIR = "shared"
    private const val IMAGE_FILE = "trajet.png"

    /**
     * `ACTION_INSERT` on [CalendarContract.Events.CONTENT_URI]: the user's calendar app opens its
     * "new event" editor prefilled; the user confirms (and picks the calendar) there. No calendar
     * permission is needed since the app never writes the provider itself. One intent = one event.
     */
    fun calendarInsertIntent(event: CalendarEventSpec): Intent =
        Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start.toEpochMilliseconds())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end.toEpochMilliseconds())
            .putExtra(CalendarContract.Events.TITLE, event.title)
            .apply {
                event.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                event.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            }

    fun addToCalendar(context: Context, event: CalendarEventSpec) {
        launch(context, calendarInsertIntent(event), "Aucune application d'agenda installée.")
    }

    /** `ACTION_SEND text/plain` through the system share sheet. */
    fun shareText(context: Context, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        launch(context, Intent.createChooser(send, "Partager le trajet"), "Aucune application de partage.")
    }

    /**
     * Writes [bitmap] as PNG to `cacheDir/shared/trajet.png` (overwritten each time, so the cache
     * holds at most one image) and shares it via [FileProvider] with a read grant. Disk work runs on
     * [Dispatchers.IO].
     */
    suspend fun shareImage(context: Context, bitmap: Bitmap, subject: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
            File(dir, IMAGE_FILE).also { out ->
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            // ClipData lets the chooser itself show the thumbnail preview and carries the grant.
            .apply { clipData = ClipData.newRawUri(subject, uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(
            context,
            Intent.createChooser(send, "Partager le trajet").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Aucune application de partage.",
        )
    }

    private fun launch(context: Context, intent: Intent, missingAppMessage: String) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, missingAppMessage, Toast.LENGTH_SHORT).show()
        }
    }
}
