package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.border
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The Apple TV look: San Francisco with size-specific tracking, critically damped springs for
 * hover and press, white capsule primary buttons, translucent circular secondary buttons, and
 * large titles. Shared by every screen so they feel like one app.
 */

/** San Francisco on macOS, the system font elsewhere. */
expect val SystemFontFamily: FontFamily

private fun style(size: Int, weight: FontWeight, tracking: Double, lineHeight: Int) = TextStyle(
    fontFamily = SystemFontFamily,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    lineHeight = lineHeight.sp,
)

/** Apple's text styles: large text gets negative tracking and tight leading, small text a little positive tracking. */
val TvTypography = Typography(
    displayLarge = style(52, FontWeight.Bold, -1.2, 56),
    displayMedium = style(44, FontWeight.Bold, -0.9, 48),
    displaySmall = style(38, FontWeight.Bold, -0.7, 42),
    headlineLarge = style(34, FontWeight.Bold, -0.6, 40),
    headlineMedium = style(28, FontWeight.Bold, -0.4, 34),
    headlineSmall = style(24, FontWeight.Bold, -0.3, 30),
    titleLarge = style(22, FontWeight.SemiBold, -0.2, 28),
    titleMedium = style(17, FontWeight.SemiBold, -0.2, 22),
    titleSmall = style(15, FontWeight.SemiBold, -0.1, 20),
    bodyLarge = style(17, FontWeight.Normal, -0.2, 24),
    bodyMedium = style(15, FontWeight.Normal, -0.1, 21),
    bodySmall = style(13, FontWeight.Normal, 0.0, 18),
    labelLarge = style(15, FontWeight.SemiBold, -0.1, 20),
    labelMedium = style(13, FontWeight.Medium, 0.0, 17),
    labelSmall = style(11, FontWeight.Medium, 0.1, 14),
)

/**
 * Hovered by the mouse or focused by a TV remote or keyboard. Everything that lights up on hover
 * uses this, so remotes show where focus is with the same highlight.
 */
@Composable
fun InteractionSource.collectIsHighlightedAsState(): State<Boolean> {
    val hovered = collectIsHoveredAsState()
    val focused = collectIsFocusedAsState()
    // Only TVs: on desktop a clicked item can keep focus, and shouldn't stay lit
    val tv = com.lagradost.cloudstream4.desktop.Platform.isTv
    return remember(hovered, focused, tv) { derivedStateOf { hovered.value || (tv && focused.value) } }
}

/** Critically damped, about 0.35 s to settle. No overshoot, as hover and press carry no momentum. */
fun <T> tvSpring() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 320f)

/** Secondary text: labels at 60%, like Apple's secondary label colour. */
val secondaryText: Color @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)

/**
 * The tvOS focus lift on hover: the item grows and casts a deeper shadow, and dips slightly the
 * instant it is pressed, before release.
 */
fun Modifier.tvLift(interaction: MutableInteractionSource, hoverScale: Float = 1.05f, cornerRadius: Dp = 12.dp): Modifier = composed {
    val hovered by interaction.collectIsHighlightedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> hoverScale - 0.03f
            hovered -> hoverScale
            else -> 1f
        },
        tvSpring(),
    )
    val focused by interaction.collectIsFocusedAsState()
    val elevation by animateFloatAsState(if (hovered) 28f else 0f, tvSpring())
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        shadowElevation = elevation
        shape = RoundedCornerShape(cornerRadius)
        clip = false
    }.then(
        // A remote has no pointer, so the focused card also gets an outline
        if (focused && com.lagradost.cloudstream4.desktop.Platform.isTv) Modifier.border(3.dp, focusRingColor, RoundedCornerShape(cornerRadius)) else Modifier
    ).hoverable(interaction)
}

/** Press feedback for buttons: a small dip on press. */
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tvSpring())
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Apple's primary action: a white capsule with dark text. */
@Composable
fun PrimaryCapsuleButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHighlightedAsState()
    // A white ring wouldn't show on a white button, so on TV the focused one turns the accent colour
    val focused by interaction.collectIsFocusedAsState()
    val accent = focused && com.lagradost.cloudstream4.desktop.Platform.isTv
    val content = if (accent) MaterialTheme.colorScheme.onPrimary else Color.Black
    Row(
        modifier
            .pressScale(interaction)
            .hoverable(interaction)
            .background(if (accent) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = if (hovered) 1f else 0.92f), RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = content, style = MaterialTheme.typography.labelLarge)
    }
}

/** Secondary action over imagery: a translucent capsule, or a circle when there is no text. */
@Composable
fun GlassButton(icon: ImageVector, description: String, text: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHighlightedAsState()
    val shape = if (text == null) CircleShape else RoundedCornerShape(50)
    // Light glass over dark imagery; on the light theme's plain backgrounds (the top bar, a page) a
    // dark glass, so the white icon and text still show
    val glass = if (isLightTheme) Color.Black.copy(alpha = if (hovered) 0.62f else 0.48f) else Color.White.copy(alpha = if (hovered) 0.28f else 0.16f)
    Row(
        modifier
            .pressScale(interaction)
            .hoverable(interaction)
            .background(glass, shape)
            .clickable(interactionSource = interaction, indication = tvIndication, onClick = onClick)
            .padding(if (text == null) PaddingValues(11.dp) else PaddingValues(horizontal = 18.dp, vertical = 11.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp))
        if (text != null) {
            Spacer(Modifier.width(6.dp))
            // One line: a long provider name ends in "…" rather than wrapping inside the pill
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
    }
}

/** The large title at the top of a screen, as in Apple's apps. */
@Composable
fun LargeTitle(
    text: String,
    modifier: Modifier = Modifier,
    /** A line under the title, such as how much the downloads take up. */
    subtitle: String? = null,
    /** False inside a page that already has the side margins (Settings, Extensions). */
    inset: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    // Every tab page starts with this, so titles share their size, left edge and spacing
    Row(
        modifier.padding(
            start = if (inset) appLayout.gutter else 0.dp,
            end = if (inset) appLayout.gutter else 0.dp,
            top = if (appLayout.compact) 16.dp else 28.dp,
            bottom = 12.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = if (appLayout.compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/**
 * The shade behind text over imagery. Always dark, whatever the theme: the text on it is white,
 * and shading towards a light theme's white page made it white on white.
 */
private val imageShade = Color(0xFF0B0B0F)

/** Darkens imagery from the middle down, then blends into the page background at the very bottom. */
fun bottomScrim(background: Color) = Brush.verticalGradient(
    0f to Color.Transparent,
    0.55f to imageShade.copy(alpha = 0.55f),
    0.88f to imageShade.copy(alpha = 0.85f),
    1f to background,
)

fun leftScrim(@Suppress("UNUSED_PARAMETER") background: Color) = Brush.horizontalGradient(
    0f to imageShade.copy(alpha = 0.92f),
    0.45f to imageShade.copy(alpha = 0.55f),
    0.75f to Color.Transparent,
)

/** Whether the app shows its light theme (then white-on-image controls need a darker base). */
val isLightTheme: Boolean @Composable get() = MaterialTheme.colorScheme.background.luminance() > 0.5f

/** The TV focus ring's colour: white on the dark theme, the accent on the light one, where white wouldn't show. */
val focusRingColor: Color @Composable get() = if (isLightTheme) MaterialTheme.colorScheme.primary else Color.White

/** Square-ish spacer used between shelves. */
@Composable
fun ShelfGap() = Spacer(Modifier.height(8.dp))

@Composable
fun Dot(selected: Boolean, onClick: () -> Unit) {
    val width by animateFloatAsState(if (selected) 22f else 7f, tvSpring())
    // The dot is small; the area that takes a tap or click around it is bigger
    Box(
        Modifier
            // Too small to aim at with a remote; TVs change slides with the arrows instead
            .focusProperties { canFocus = !com.lagradost.cloudstream4.desktop.Platform.isTv }
            .clickable(indication = null, interactionSource = null, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 12.dp)
    ) {
        Box(Modifier.height(7.dp).width(width.dp).background(Color.White.copy(alpha = if (selected) 0.95f else 0.4f), RoundedCornerShape(50)))
    }
}
