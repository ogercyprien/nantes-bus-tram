package com.bustramnantes.app.presentation.common.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Static (non-dynamic-color) fallback palette, used on API < 31 or when the user disables
 * dynamic color. Seeded around a transit-app blue/teal - Naolib itself has no single fixed brand
 * hue across bus/tram lines, so this is a neutral app identity color, not a line color.
 *
 * Individual line colors always come from the API and are rendered via
 * [com.bustramnantes.app.presentation.common.components.LineBadge], never from this palette.
 */

// Brand seed - "Naolib teal/blue".
internal val BrandPrimaryLight = Color(0xFF00658F)
internal val BrandOnPrimaryLight = Color(0xFFFFFFFF)
internal val BrandPrimaryContainerLight = Color(0xFFC5E7FF)
internal val BrandOnPrimaryContainerLight = Color(0xFF001E2C)

internal val BrandSecondaryLight = Color(0xFF4E616C)
internal val BrandOnSecondaryLight = Color(0xFFFFFFFF)
internal val BrandSecondaryContainerLight = Color(0xFFD1E5F2)
internal val BrandOnSecondaryContainerLight = Color(0xFF0A1D26)

internal val BrandTertiaryLight = Color(0xFF5D5B7D)
internal val BrandOnTertiaryLight = Color(0xFFFFFFFF)
internal val BrandTertiaryContainerLight = Color(0xFFE3DFFF)
internal val BrandOnTertiaryContainerLight = Color(0xFF191837)

internal val BrandErrorLight = Color(0xFFBA1A1A)
internal val BrandOnErrorLight = Color(0xFFFFFFFF)
internal val BrandErrorContainerLight = Color(0xFFFFDAD6)
internal val BrandOnErrorContainerLight = Color(0xFF410002)

internal val BrandBackgroundLight = Color(0xFFF7FAFD)
internal val BrandOnBackgroundLight = Color(0xFF181C1E)
internal val BrandSurfaceLight = Color(0xFFF7FAFD)
internal val BrandOnSurfaceLight = Color(0xFF181C1E)
internal val BrandSurfaceVariantLight = Color(0xFFDCE4E9)
internal val BrandOnSurfaceVariantLight = Color(0xFF40484C)
internal val BrandOutlineLight = Color(0xFF71797D)

internal val BrandPrimaryDark = Color(0xFF8ECFFF)
internal val BrandOnPrimaryDark = Color(0xFF003548)
internal val BrandPrimaryContainerDark = Color(0xFF004C69)
internal val BrandOnPrimaryContainerDark = Color(0xFFC5E7FF)

internal val BrandSecondaryDark = Color(0xFFB5C9D6)
internal val BrandOnSecondaryDark = Color(0xFF20333C)
internal val BrandSecondaryContainerDark = Color(0xFF364A53)
internal val BrandOnSecondaryContainerDark = Color(0xFFD1E5F2)

internal val BrandTertiaryDark = Color(0xFFC6C2EA)
internal val BrandOnTertiaryDark = Color(0xFF2E2D4D)
internal val BrandTertiaryContainerDark = Color(0xFF454364)
internal val BrandOnTertiaryContainerDark = Color(0xFFE3DFFF)

internal val BrandErrorDark = Color(0xFFFFB4AB)
internal val BrandOnErrorDark = Color(0xFF690005)
internal val BrandErrorContainerDark = Color(0xFF93000A)
internal val BrandOnErrorContainerDark = Color(0xFFFFDAD6)

internal val BrandBackgroundDark = Color(0xFF101416)
internal val BrandOnBackgroundDark = Color(0xFFE0E3E5)
internal val BrandSurfaceDark = Color(0xFF101416)
internal val BrandOnSurfaceDark = Color(0xFFE0E3E5)
internal val BrandSurfaceVariantDark = Color(0xFF40484C)
internal val BrandOnSurfaceVariantDark = Color(0xFFC0C8CD)
internal val BrandOutlineDark = Color(0xFF8A9297)

internal val LightColorScheme = lightColorScheme(
    primary = BrandPrimaryLight,
    onPrimary = BrandOnPrimaryLight,
    primaryContainer = BrandPrimaryContainerLight,
    onPrimaryContainer = BrandOnPrimaryContainerLight,
    secondary = BrandSecondaryLight,
    onSecondary = BrandOnSecondaryLight,
    secondaryContainer = BrandSecondaryContainerLight,
    onSecondaryContainer = BrandOnSecondaryContainerLight,
    tertiary = BrandTertiaryLight,
    onTertiary = BrandOnTertiaryLight,
    tertiaryContainer = BrandTertiaryContainerLight,
    onTertiaryContainer = BrandOnTertiaryContainerLight,
    error = BrandErrorLight,
    onError = BrandOnErrorLight,
    errorContainer = BrandErrorContainerLight,
    onErrorContainer = BrandOnErrorContainerLight,
    background = BrandBackgroundLight,
    onBackground = BrandOnBackgroundLight,
    surface = BrandSurfaceLight,
    onSurface = BrandOnSurfaceLight,
    surfaceVariant = BrandSurfaceVariantLight,
    onSurfaceVariant = BrandOnSurfaceVariantLight,
    outline = BrandOutlineLight,
)

internal val DarkColorScheme = darkColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = BrandOnPrimaryDark,
    primaryContainer = BrandPrimaryContainerDark,
    onPrimaryContainer = BrandOnPrimaryContainerDark,
    secondary = BrandSecondaryDark,
    onSecondary = BrandOnSecondaryDark,
    secondaryContainer = BrandSecondaryContainerDark,
    onSecondaryContainer = BrandOnSecondaryContainerDark,
    tertiary = BrandTertiaryDark,
    onTertiary = BrandOnTertiaryDark,
    tertiaryContainer = BrandTertiaryContainerDark,
    onTertiaryContainer = BrandOnTertiaryContainerDark,
    error = BrandErrorDark,
    onError = BrandOnErrorDark,
    errorContainer = BrandErrorContainerDark,
    onErrorContainer = BrandOnErrorContainerDark,
    background = BrandBackgroundDark,
    onBackground = BrandOnBackgroundDark,
    surface = BrandSurfaceDark,
    onSurface = BrandOnSurfaceDark,
    surfaceVariant = BrandSurfaceVariantDark,
    onSurfaceVariant = BrandOnSurfaceVariantDark,
    outline = BrandOutlineDark,
)
