package com.bustramnantes.app.presentation.common.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * User-selectable theme preference (Settings > Thème). The Settings feature
 * agent persists the chosen value in DataStore and reads it back into [BusEtTramTheme]; this
 * shared module only defines the enum and the theming logic that consumes it.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * App-wide Material3 theme. Applies dynamic color (Material You) on Android 12+ (API 31+)
 * when [dynamicColor] is true, falling back to the static [LightColorScheme]/[DarkColorScheme]
 * on older devices or when dynamic color is disabled/unsupported.
 *
 * @param themeMode drives light/dark regardless of dynamic color; SYSTEM follows the OS setting.
 */
@Composable
fun BusEtTramTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
