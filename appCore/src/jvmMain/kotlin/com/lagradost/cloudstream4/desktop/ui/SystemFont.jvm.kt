package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import com.lagradost.cloudstream4.desktop.Platform

/** San Francisco, the macOS system font, in the weights the app uses. Other systems use their default font. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
actual val SystemFontFamily: FontFamily =
    if (Platform.isMac) {
        FontFamily(
            SystemFont(".AppleSystemUIFont", FontWeight.Normal),
            SystemFont(".AppleSystemUIFont", FontWeight.Medium),
            SystemFont(".AppleSystemUIFont", FontWeight.SemiBold),
            SystemFont(".AppleSystemUIFont", FontWeight.Bold),
            SystemFont(".AppleSystemUIFont", FontWeight.ExtraBold),
        )
    } else {
        FontFamily.Default
    }
