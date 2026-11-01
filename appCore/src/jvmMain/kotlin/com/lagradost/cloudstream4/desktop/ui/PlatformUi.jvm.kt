package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun HoverTooltip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF2A2A2A)) {
                Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp).widthIn(max = 420.dp))
            }
        },
        delayMillis = 700,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
        content = content,
    )
}

/** Fullscreen through the app window. */
class WindowFullscreen(private val window: WindowState) : FullscreenControl {
    override val isFullscreen: Boolean get() = window.placement == WindowPlacement.Fullscreen
    override fun setFullscreen(on: Boolean) {
        window.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

/** A fully transparent cursor: AWT has no "no cursor", so it's a blank image. */
private val blankCursor by lazy {
    androidx.compose.ui.input.pointer.PointerIcon(
        java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
            java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            java.awt.Point(0, 0),
            "hidden",
        )
    )
}

// Always in the chain, with only the icon changing, so hiding and showing take effect at once,
// without waiting for the mouse to move. Shown, buttons keep their own cursors
actual fun Modifier.hideCursor(hidden: Boolean): Modifier = pointerHoverIcon(
    if (hidden) blankCursor else androidx.compose.ui.input.pointer.PointerIcon.Default,
    overrideDescendants = hidden,
)
