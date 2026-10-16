package com.bustramnantes.app.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.presentation.common.utils.formatDisruptionWhen
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Disruption accent (amber, readable on both themes; the error red stays for failures). */
val DisruptionAmber = Color(0xFFE8A200)

/**
 * A [LineBadge] with, when [hasDisruption], a small amber dot on its top-right corner: "this line has
 * a disruption notice in force". Same parameters as [LineBadge] otherwise.
 */
@Composable
fun LineBadgeWithAlert(
    shortName: String,
    backgroundColorHex: String,
    textColorHex: String,
    mode: com.bustramnantes.app.domain.model.TransportMode,
    hasDisruption: Boolean,
    modifier: Modifier = Modifier,
    size: LineBadgeSize = LineBadgeSize.Medium,
) {
    if (!hasDisruption) {
        LineBadge(shortName, backgroundColorHex, textColorHex, mode, modifier, size)
        return
    }
    Box(modifier = modifier.semantics { contentDescription = "Ligne $shortName, perturbée" }) {
        LineBadge(shortName, backgroundColorHex, textColorHex, mode, size = size)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 3.dp, y = (-3).dp)
                .size(if (size == LineBadgeSize.Small) 8.dp else 10.dp)
                .clip(CircleShape)
                .background(DisruptionAmber)
                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
        )
    }
}

/** [LineBadgeWithAlert] for a domain [Line]. */
@Composable
fun LineBadgeWithAlert(line: Line, hasDisruption: Boolean, modifier: Modifier = Modifier, size: LineBadgeSize = LineBadgeSize.Medium) =
    LineBadgeWithAlert(line.shortName, line.color, line.textColor, line.mode, hasDisruption, modifier, size)

/**
 * One notice as a compact row: amber warning icon, title (two lines max), "Jusqu'au 20/11"-style
 * timing and the badges of the [lines] it concerns. Tapping calls [onClick] (open the full notice).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisruptionRow(
    disruption: Disruption,
    lines: List<Line>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Clock.System.now(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = DisruptionAmber,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                text = disruption.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatDisruptionWhen(disruption, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lines.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    lines.forEach { LineBadge(it, size = LineBadgeSize.Small) }
                }
            }
        }
    }
}

/**
 * A boxed list of notices (Stop Detail, an itinerary leg): up to [collapsedCount] rows, then a
 * "Voir les N perturbations" toggle. [linesOf] resolves the badges of a notice; [onOpen] shows one.
 */
@Composable
fun DisruptionBox(
    disruptions: List<Disruption>,
    linesOf: (Disruption) -> List<Line>,
    onOpen: (Disruption) -> Unit,
    modifier: Modifier = Modifier,
    header: String? = null,
    collapsedCount: Int = 2,
) {
    if (disruptions.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DisruptionAmber.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        header?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val shown = if (expanded) disruptions else disruptions.take(collapsedCount)
        shown.forEach { d -> DisruptionRow(d, linesOf(d), onClick = { onOpen(d) }) }
        if (disruptions.size > collapsedCount) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Réduire" else "Voir les ${disruptions.size} perturbations")
            }
        }
    }
}

/**
 * The full notice: title, timing, line badges and the publisher's HTML description (paragraphs,
 * bold, links to diversion maps - links open in the browser).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisruptionDialog(
    disruption: Disruption,
    lines: List<Line>,
    onDismiss: () -> Unit,
    now: Instant = Clock.System.now(),
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val description = remember(disruption.descriptionHtml, linkColor) {
        AnnotatedString.fromHtml(
            disruption.descriptionHtml,
            linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
        )
    }
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = DisruptionAmber) },
        title = { Text(disruption.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = formatDisruptionWhen(disruption, now),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (lines.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        lines.forEach { LineBadge(it, size = LineBadgeSize.Small) }
                    }
                }
                if (description.isNotBlank()) {
                    Text(text = description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
                disruption.url?.let { url ->
                    TextButton(onClick = { runCatching { uriHandler.openUri(url) } }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Plus d'informations")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
