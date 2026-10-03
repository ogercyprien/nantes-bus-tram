package com.bustramnantes.app.presentation.itinerary

import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import com.bustramnantes.app.presentation.itinerary.share.JourneyShareButton
import com.bustramnantes.app.presentation.itinerary.share.JourneyShareIntents
import com.bustramnantes.app.presentation.itinerary.share.formatFrenchDate
import com.bustramnantes.app.presentation.itinerary.share.journeyTitle
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.ConcernedStop
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.presentation.common.components.DisruptionBox
import com.bustramnantes.app.presentation.common.components.LineBadgeWithAlert
import com.bustramnantes.app.domain.model.Journey
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.TimeSourceType
import com.bustramnantes.app.presentation.common.components.LineBadge
import com.bustramnantes.app.presentation.common.components.LineBadgeSize
import com.bustramnantes.app.presentation.common.utils.PHANTOM_DEPARTURE_LABEL

/**
 * Step-by-step detail view for one [Journey]: transit legs show line/direction
 * and every concerned stop with its time; walking legs render the compass-direction summary from
 * [formatWalkingSummary]. No map thumbnail.
 *
 * The header carries the share menu ([JourneyShareButton]: calendar / text / image).
 * [fromLabel]/[toLabel] are the search's endpoint labels, used to title shared trips.
 *
 * **Share as image.** Android offers apps no "scroll capture" API, and the [LazyColumn] only
 * composes what is on screen, so the image is a separate, *non-scrolling* render of the whole detail
 * ([JourneyShareImageContent]) composed only while a capture is pending. It is measured with
 * unbounded height but reports a 0x0 size to its parent, and its draw pass is *recorded* into a
 * `GraphicsLayer` (`rememberGraphicsLayer()` + `record { drawContent() }`) without ever being drawn
 * to the screen; a frame later `GraphicsLayer.toImageBitmap()` yields the full-height bitmap,
 * shared as PNG by [JourneyShareIntents.shareImage].
 */
@Composable
fun JourneyDetailContent(
    journey: Journey,
    onStopClick: (stopId: String) -> Unit,
    modifier: Modifier = Modifier,
    timetableRoutes: Map<JourneySection.Transit, String> = emptyMap(),
    onOpenTimetable: (route: String) -> Unit = {},
    fromLabel: String = "",
    toLabel: String = "",
    legDisruptions: Map<JourneySection.Transit, List<Disruption>> = emptyMap(),
    linesOf: (Disruption) -> List<Line> = { emptyList() },
    onOpenDisruption: (Disruption) -> Unit = {},
) {
    val context = LocalContext.current
    var isCapturingImage by remember { mutableStateOf(false) }
    val captureLayer = rememberGraphicsLayer()

    if (isCapturingImage) {
        LaunchedEffect(journey) {
            try {
                // Frame 1 composes/lays out/draws (= records) the off-screen render; wait one more
                // to be sure the recording has happened before reading the layer back.
                withFrameNanos { }
                withFrameNanos { }
                val bitmap = captureLayer.toImageBitmap().asAndroidBitmap().let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.config == Bitmap.Config.HARDWARE) {
                        it.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        it
                    }
                }
                JourneyShareIntents.shareImage(context, bitmap, journeyTitle(journey, fromLabel, toLabel))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Impossible de créer l'image du trajet.", Toast.LENGTH_SHORT).show()
            } finally {
                isCapturingImage = false
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                JourneyDetailHeader(
                    journey = journey,
                    shareAction = {
                        JourneyShareButton(
                            journey = journey,
                            fromLabel = fromLabel,
                            toLabel = toLabel,
                            onShareImage = { isCapturingImage = true },
                            isImageBusy = isCapturingImage,
                        )
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            itemsIndexed(journey.sections) { index, section ->
                JourneySectionItem(
                    section = section,
                    onStopClick = onStopClick,
                    onOpenTimetable = (section as? JourneySection.Transit)
                        ?.let { timetableRoutes[it] }
                        ?.let { route -> { onOpenTimetable(route) } },
                    disruptions = (section as? JourneySection.Transit)?.let { legDisruptions[it] }.orEmpty(),
                    linesOf = linesOf,
                    onOpenDisruption = onOpenDisruption,
                )
                if (index != journey.sections.lastIndex) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        if (isCapturingImage) {
            JourneyShareImageContent(
                journey = journey,
                fromLabel = fromLabel,
                toLabel = toLabel,
                modifier = Modifier
                    .clearAndSetSemantics { }
                    // Measure at the screen's width with unbounded height, but take no room.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
                        )
                        layout(0, 0) { placeable.place(0, 0) }
                    }
                    // Record only - never drawn on screen.
                    .drawWithContent { captureLayer.record { this@drawWithContent.drawContent() } },
            )
        }
    }
}

/** One leg of the detail, shared by the scrolling list and the share-image render. */
@Composable
private fun JourneySectionItem(
    section: JourneySection,
    onStopClick: ((stopId: String) -> Unit)?,
    onOpenTimetable: (() -> Unit)?,
    disruptions: List<Disruption> = emptyList(),
    linesOf: (Disruption) -> List<Line> = { emptyList() },
    onOpenDisruption: (Disruption) -> Unit = {},
) {
    when (section) {
        is JourneySection.Walking -> WalkingSectionRow(section)
        is JourneySection.Transit -> TransitSectionCard(
            section = section,
            onStopClick = onStopClick,
            onOpenTimetable = onOpenTimetable,
            disruptions = disruptions,
            linesOf = linesOf,
            onOpenDisruption = onOpenDisruption,
        )
    }
}

/**
 * The full, non-scrolling itinerary as it appears in the shared image: a title + date header, the
 * usual summary, then every leg (no buttons, stops not clickable) on an opaque background. No map.
 */
@Composable
private fun JourneyShareImageContent(
    journey: Journey,
    fromLabel: String,
    toLabel: String,
    modifier: Modifier = Modifier,
) {
    val zone = TimeZone.currentSystemDefault()
    val tripDate = journey.departureTime.toLocalDateTime(zone).date
    val dateLabel = formatFrenchDate(tripDate).replaceFirstChar { it.uppercase() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Text(
            text = journeyTitle(journey, fromLabel, toLabel),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        JourneyDetailHeader(journey)
        Spacer(modifier = Modifier.height(16.dp))
        journey.sections.forEachIndexed { index, section ->
            JourneySectionItem(section = section, onStopClick = null, onOpenTimetable = null)
            if (index != journey.sections.lastIndex) Spacer(modifier = Modifier.height(12.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Bus et Tram Nantes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@Composable
private fun JourneyDetailHeader(
    journey: Journey,
    modifier: Modifier = Modifier,
    shareAction: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${formatClockTime(journey.departureTime)} → ${formatClockTime(journey.arrivalTime)}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatJourneyDuration(journey.totalDurationSeconds),
                style = MaterialTheme.typography.titleLarge,
            )
            shareAction?.invoke()
        }
        Text(
            text = formatTransferCount(journey.transferCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WalkingSectionRow(section: JourneySection.Walking, modifier: Modifier = Modifier) {
    Text(
        text = formatWalkingSummary(section),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

@Composable
private fun TransitSectionCard(
    section: JourneySection.Transit,
    onStopClick: ((stopId: String) -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenTimetable: (() -> Unit)? = null,
    disruptions: List<Disruption> = emptyList(),
    linesOf: (Disruption) -> List<Line> = { emptyList() },
    onOpenDisruption: (Disruption) -> Unit = {},
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadgeWithAlert(
                    shortName = section.lineNumber,
                    backgroundColorHex = section.lineColor,
                    textColorHex = section.lineTextColor,
                    mode = section.transportMode,
                    hasDisruption = disruptions.isNotEmpty(),
                    size = LineBadgeSize.Medium,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Direction ${section.directionName}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isRealtime = section.timeSourceType == TimeSourceType.REALTIME
                        Icon(
                            imageVector = if (isRealtime) Icons.Filled.Circle else Icons.Outlined.Circle,
                            contentDescription = if (isRealtime) "Temps réel" else "Horaire théorique",
                            modifier = Modifier.size(8.dp),
                            tint = if (isRealtime) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = buildAnnotatedString {
                                // A suspected phantom's departure is struck through, as on the boards.
                                if (section.isSuspectedPhantom) {
                                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                                        append(formatClockTime(section.departureTime))
                                    }
                                } else {
                                    append(formatClockTime(section.departureTime))
                                }
                                append(" → ${formatClockTime(section.arrivalTime)}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Only reachable when every option rode a phantom (otherwise those journeys were
                    // replaced): say plainly that this bus may not come.
                    if (section.isSuspectedPhantom) {
                        Text(
                            text = "Passage $PHANTOM_DEPARTURE_LABEL : aucun véhicule n'est suivi pour ce bus",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (disruptions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                DisruptionBox(disruptions = disruptions, linesOf = linesOf, onOpen = onOpenDisruption, collapsedCount = 1)
            }
            if (onOpenTimetable != null) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = onOpenTimetable,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Horaires théoriques")
                }
            }
            if (section.concernedStops.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                section.concernedStops.forEachIndexed { index, stop ->
                    ConcernedStopRow(stop = stop, onClick = onStopClick)
                    if (index != section.concernedStops.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ConcernedStopRow(
    stop: ConcernedStop,
    /** `null` = not clickable (share-image render). */
    onClick: ((stopId: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null && stop.stopId.isNotBlank()) { onClick?.invoke(stop.stopId) }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stop.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
        Text(
            text = stop.departureTime?.let { formatClockTime(it) } ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
