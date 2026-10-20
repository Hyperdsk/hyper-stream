package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizes that change with the window: the Mac app and tablets and TVs use [Regular], phones in
 * portrait (under 600dp wide) use [Compact], with a bottom bar instead of the sidebar, narrower
 * margins and smaller posters.
 */
@Immutable
data class AppLayout(
    val compact: Boolean,
    /** Left and right margin of screen content. */
    val gutter: Dp,
    val poster: Dp,
    val widePoster: Dp,
    /** Space between cards in a shelf. */
    val shelfSpacing: Dp,
    /** Continue watching and review cards. */
    val card: Dp,
    /** The big page margin of the details and hero areas. */
    val pageMargin: Dp,
) {
    companion object {
        val Regular = AppLayout(compact = false, gutter = 32.dp, poster = 168.dp, widePoster = 300.dp, shelfSpacing = 20.dp, card = 320.dp, pageMargin = 48.dp)
        val Compact = AppLayout(compact = true, gutter = 16.dp, poster = 112.dp, widePoster = 232.dp, shelfSpacing = 12.dp, card = 260.dp, pageMargin = 16.dp)

        /** Phones in portrait. Landscape phones, tablets, TVs and desktop windows keep the regular layout. */
        fun forWidth(width: Dp) = if (width < 600.dp) Compact else Regular
    }
}

val LocalAppLayout = staticCompositionLocalOf { AppLayout.Regular }

val appLayout: AppLayout
    @Composable @ReadOnlyComposable get() = LocalAppLayout.current
