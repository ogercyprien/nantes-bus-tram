package com.bustramnantes.app.presentation.itinerary.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.presentation.itinerary.formatClockTime
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Share icon + overflow menu for the journey detail: "Ajouter au calendrier" (opens
 * [JourneyCalendarDialog]), "Partager en texte" (system share sheet with [buildJourneyShareText])
 * and "Partager en image" (delegated to [onShareImage]: the caller owns the off-screen render of the
 * full detail, see `JourneyDetailContent`).
 *
 * [fromLabel]/[toLabel] are the search's endpoint labels (stop, address or current position).
 */
@Composable
fun JourneyShareButton(
    journey: Journey,
    fromLabel: String,
    toLabel: String,
    onShareImage: () -> Unit,
    modifier: Modifier = Modifier,
    isImageBusy: Boolean = false,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var calendarDialogOpen by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { menuOpen = true }) {
            Icon(imageVector = Icons.Outlined.Share, contentDescription = "Partager le trajet")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Ajouter au calendrier") },
                leadingIcon = { Icon(Icons.Outlined.Event, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    calendarDialogOpen = true
                },
            )
            DropdownMenuItem(
                text = { Text("Partager en texte") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    val zone = TimeZone.currentSystemDefault()
                    JourneyShareIntents.shareText(
                        context = context,
                        subject = journeyTitle(journey, fromLabel, toLabel),
                        text = buildJourneyShareText(
                            journey = journey,
                            fromLabel = fromLabel,
                            toLabel = toLabel,
                            zone = zone,
                            today = Clock.System.todayIn(zone),
                        ),
                    )
                },
            )
            DropdownMenuItem(
                text = { Text("Partager en image") },
                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                enabled = !isImageBusy,
                onClick = {
                    menuOpen = false
                    onShareImage()
                },
            )
        }
    }

    if (calendarDialogOpen) {
        JourneyCalendarDialog(
            journey = journey,
            fromLabel = fromLabel,
            toLabel = toLabel,
            onDismiss = { calendarDialogOpen = false },
        )
    }
}

/**
 * "Ajouter au calendrier" chooser.
 *
 * One `ACTION_INSERT` intent creates exactly one event and there is no batch variant that works
 * without the calendar permission, so the dialog lists every option with its own "Ajouter" button:
 * - **Trajet complet**: one event spanning the whole trip, legs listed in the description;
 * - **one row per bus/tram ride**: `"Bus C2 → Talensac"` at the boarding stop.
 *
 * Tapping a button opens the calendar app prefilled; the dialog stays open underneath, so after
 * saving the user comes back to it and adds the next leg. Rows already sent are ticked ("Ajouté")
 * so it is obvious where they left off (still tappable, in case the user cancelled in the calendar).
 */
@Composable
private fun JourneyCalendarDialog(
    journey: Journey,
    fromLabel: String,
    toLabel: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { TimeZone.currentSystemDefault() }
    val rides = remember(journey, fromLabel, toLabel) {
        journey.toShareLegs(fromLabel, toLabel).filterIsInstance<ShareLeg.Ride>()
    }
    // Index -1 = the whole-trip event, 0..n = rides[i].
    val added = remember(journey) { mutableStateListOf<Int>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter au calendrier") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Votre agenda s'ouvre pré-rempli : enregistrez l'événement, puis revenez ici " +
                        "pour ajouter le suivant.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                CalendarOptionRow(
                    title = "Trajet complet",
                    subtitle = "${formatClockTime(journey.departureTime, zone)}-" +
                        formatClockTime(journey.arrivalTime, zone),
                    isAdded = -1 in added,
                    onAdd = {
                        JourneyShareIntents.addToCalendar(
                            context,
                            buildJourneyCalendarEvent(journey, fromLabel, toLabel, zone),
                        )
                        if (-1 !in added) added += -1
                    },
                )
                if (rides.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "Un événement par trajet en bus/tram",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    rides.forEachIndexed { index, ride ->
                        val event = buildLegCalendarEvent(ride, zone)
                        CalendarOptionRow(
                            title = event.title,
                            subtitle = "${formatClockTime(ride.start, zone)}-${formatClockTime(ride.end, zone)}" +
                                (event.location?.let { " · $it" } ?: ""),
                            isAdded = index in added,
                            onAdd = {
                                JourneyShareIntents.addToCalendar(context, event)
                                if (index !in added) added += index
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        },
    )
}

@Composable
private fun CalendarOptionRow(
    title: String,
    subtitle: String,
    isAdded: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isAdded) {
            OutlinedButton(onClick = onAdd) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Ajouté")
            }
        } else {
            FilledTonalButton(onClick = onAdd) { Text("Ajouter") }
        }
    }
}
