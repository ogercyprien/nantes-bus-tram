package com.bustramnantes.app.presentation.common.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.presentation.common.theme.BusEtTramTheme

/**
 * Design-time previews for [LineBadge]. The sandbox has no device, so these `@Preview`s (rendered by
 * the IDE / `ui-tooling`) are the fastest way to eyeball badge shape, sizing, and contrast handling
 * for both transport modes. Preview-only - excluded from the release build by R8.
 */
@Preview(name = "Line badges - bus & tram, all sizes", showBackground = true)
@Composable
private fun LineBadgePreview() {
    BusEtTramTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf(LineBadgeSize.Small, LineBadgeSize.Medium, LineBadgeSize.Large).forEach { size ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Bus: circular pill. Tram: taller rounded rectangle (matches Nantes signage).
                    LineBadge("4", "#FFCD1C", "#000000", TransportMode.BUS, size = size)
                    LineBadge("C2", "#00A0E3", "#FFFFFF", TransportMode.BUS, size = size)
                    LineBadge("1", "#009EE0", "#FFFFFF", TransportMode.TRAM, size = size)
                    // Low-contrast API pair -> foreground auto-corrected to stay WCAG-legible.
                    LineBadge("2", "#FFFF00", "#FFFFFF", TransportMode.TRAM, size = size)
                }
            }
        }
    }
}
