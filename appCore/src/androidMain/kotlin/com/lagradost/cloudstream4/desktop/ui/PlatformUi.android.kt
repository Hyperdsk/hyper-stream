package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

@Composable
actual fun HoverTooltip(text: String, content: @Composable () -> Unit) = content()

/** Roboto, Android's system font. */
actual val SystemFontFamily: FontFamily = FontFamily.Default

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled, onBack)
