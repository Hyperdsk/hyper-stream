package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.Platform
import kotlinx.coroutines.launch

/**
 * For custom buttons on TV: a white ring while focused, since a remote has no pointer and a
 * slight brightening is too hard to see from the sofa. Null elsewhere, so desktop and phones
 * look as before.
 */
val tvIndication: Indication? get() = if (Platform.isTv) FocusRingIndication else null

private object FocusRingIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = FocusRingNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 1
}

private class FocusRingNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            var count = 0
            source.interactions.collect {
                when (it) {
                    is FocusInteraction.Focus -> count++
                    is FocusInteraction.Unfocus -> count--
                }
                val now = count > 0
                if (now != focused) {
                    focused = now
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val width = 3.dp.toPx()
        // Capsules get a capsule ring, larger shapes rounded corners
        val radius = minOf(size.height / 2f, 14.dp.toPx())
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(width / 2, width / 2),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(radius),
            style = Stroke(width),
        )
    }
}
