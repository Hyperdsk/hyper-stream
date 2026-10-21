package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.lagradost.cloudstream4.theme.CloudStreamPrimaryColor
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode

/** The Hyper Stream blue, from the app icon. */
val HyperStreamBlue = Color(0xFF0A47F8)

/** The accent swatch shown in Settings: the default accent is the brand blue. */
val CloudStreamPrimaryColor.swatch: Color get() = if (this == CloudStreamPrimaryColor.NORMAL) HyperStreamBlue else color

/**
 * The app's theme: CloudStream's light and dark palettes with the Hyper Stream blue as the
 * accent (unless another accent is picked in Settings), and the system font.
 */
@Composable
fun HyperStreamTheme(mode: CloudStreamThemeMode, primaryColor: CloudStreamPrimaryColor, content: @Composable () -> Unit) {
    CloudStreamTheme(mode = mode, primaryColor = primaryColor) {
        val base = MaterialTheme.colorScheme
        val scheme = if (primaryColor == CloudStreamPrimaryColor.NORMAL) {
            val dark = base.background.luminance() < 0.5f
            base.copy(
                primary = HyperStreamBlue,
                onPrimary = Color.White,
                primaryContainer = lerp(HyperStreamBlue, base.surface, if (dark) 0.55f else 0.8f),
                onPrimaryContainer = if (dark) Color.White else Color(0xFF001A66),
                inversePrimary = lerp(HyperStreamBlue, Color.White, 0.45f),
                secondary = lerp(HyperStreamBlue, Color.White, if (dark) 0.3f else 0f),
                onSecondary = Color.White,
                surfaceTint = HyperStreamBlue,
            )
        } else base
        MaterialTheme(colorScheme = scheme, typography = TvTypography, content = content)
    }
}
