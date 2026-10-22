package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import com.lagradost.cloudstream4.desktop.player.DeviceControls
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import com.lagradost.cloudstream4.desktop.player.VideoFit
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.player.PlaybackState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import com.lagradost.cloudstream4.desktop.player.SeekPreview
import com.lagradost.cloudstream4.desktop.player.VideoEngine
import com.lagradost.cloudstream4.desktop.player.StreamGateway
import com.lagradost.cloudstream4.desktop.player.StreamStats
import com.lagradost.cloudstream4.desktop.Platform
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.wrapContentHeight

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(model: PlayerModel, onExit: () -> Unit) {
    val ui by model.state.collectAsState()
    val player = model.player
    val playback by (player?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(PlaybackState()) }).collectAsState()
    val settings by SettingsStore.state.collectAsState()
    val stats by StreamGateway.stats.collectAsState()
    // Seconds spent buffering at a crawl, for the "slow source" hint
    var slowSeconds by remember { mutableIntStateOf(0) }
    // Seconds spent buffering at all
    var slowSecondsAny by remember { mutableIntStateOf(0) }
    val window = LocalFullscreen.current
    val focus = remember { FocusRequester() }
    val holdScope = rememberCoroutineScope()
    val hold = remember { SpeedHold(holdScope) }

    // Seek bar thumbnails, from a second silent player on the same video
    val preview = remember(player) { player?.createPreview() }
    DisposableEffect(preview) { onDispose { preview?.release() } }
    val currentUrl = player?.currentUrl
    // Open the preview player only once the video plays, and at low priority ("?preview"), so it
    // never competes with the main player for bandwidth while that is starting up
    // VLC reports "playing" before it has a frame; wait for the clock to actually move
    val startedPlaying = playback.timeMs > 0
    LaunchedEffect(preview, currentUrl, startedPlaying) {
        preview?.open(currentUrl?.takeIf { startedPlaying }?.let(StreamGateway::previewUrl))
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var lastMove by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var openMenu by remember { mutableStateOf<String?>(null) }

    // The intro, recap or credits the playhead is in, from the skip databases. Its Skip button shows
    // for the whole section, unless auto-skip is about to jump over it
    val activeSkip = model.activeSkip(playback.timeMs)
    val showSkip = activeSkip != null && openMenu == null && !(settings.autoSkipIntros && activeSkip.stamp.type.isIntro)

    val fullscreen = window?.isFullscreen == true
    fun toggleFullscreen() {
        window?.setFullscreen(!fullscreen)
    }
    fun exit() {
        if (fullscreen) window?.setFullscreen(false)
        onExit()
    }
    fun poke() {
        lastMove = System.currentTimeMillis()
        controlsVisible = true
    }

    val touch = Platform.isTouch
    val tv = Platform.isTv
    // The play button, focused when a remote brings up the controls
    val playFocus = remember { FocusRequester() }
    // Double-tap seek feedback: -1 back, 1 forward, with a counter so repeats restart it
    var seekFlash by remember { mutableStateOf<Pair<Int, Long>?>(null) }

    // Picture size: the fit mode is remembered between videos, zoom and pan only while watching
    val fit = VideoFit.from(settings.videoFit)
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    // A short label in the middle when the fit or zoom changes
    var pictureFlash by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(pictureFlash) {
        if (pictureFlash != null) {
            delay(1_200)
            pictureFlash = null
        }
    }
    /** Keeps the zoomed picture covering the screen: it can't be dragged past its edges. */
    fun clampPan(p: Offset, z: Float): Offset {
        val maxX = (z - 1f) * area.width / 2f
        val maxY = (z - 1f) * area.height / 2f
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }
    fun setZoom(z: Float, announce: Boolean = true) {
        zoom = z.coerceIn(1f, 4f)
        pan = clampPan(pan, zoom)
        if (announce) pictureFlash = "Zoom ${(zoom * 100).roundToInt()}%" to System.nanoTime()
    }
    // Brightness and volume swipes: which one (true = brightness) and its level, while it shows
    var level by remember { mutableStateOf<Pair<Boolean, Float>?>(null) }
    var levelShownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(levelShownAt) {
        if (levelShownAt != 0L) {
            delay(900)
            level = null
        }
    }
    // Brightness is only for watching: the phone's setting comes back when the player closes
    DisposableEffect(Unit) { onDispose { DeviceControls.resetBrightness() } }

    fun setFit(f: VideoFit) {
        SettingsStore.update { it.copy(videoFit = f.name) }
        zoom = 1f
        pan = Offset.Zero
        pictureFlash = f.label to System.nanoTime()
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Remotes: the controls take focus when they appear and give it back to the player when they hide
    LaunchedEffect(controlsVisible, openMenu) {
        if (!tv || openMenu != null) return@LaunchedEffect
        delay(50)
        runCatching { if (controlsVisible) playFocus.requestFocus() else focus.requestFocus() }
    }
    LaunchedEffect(seekFlash) {
        if (seekFlash != null) {
            delay(650)
            seekFlash = null
        }
    }
    // Android's back button and gesture: close the panel first, then leave the player
    PlatformBackHandler {
        if (openMenu != null) openMenu = null else exit()
    }
    // Hide controls after a few seconds without mouse movement while playing
    LaunchedEffect(lastMove, playback.playing, openMenu) {
        if (playback.playing && openMenu == null) {
            delay(3_000)
            controlsVisible = false
        } else {
            controlsVisible = true
        }
    }

    val seekMs = settings.seekSeconds * 1000L
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Keys only reach the player while it or one of its controls has focus. A clicked button
            // that fades out with the controls, or a closed panel, would take focus with it and
            // leave the keyboard dead until the next click. Menus and the panel keep theirs
            .reclaimFocus(focus, paused = openMenu != null)
            .onPointerEvents { if (it.type == PointerEventType.Move) poke() }
            .onPreviewKeyEvent { e ->
                if (player == null) return@onPreviewKeyEvent false
                // With the side panel open, keys go to it (its filter box), except Escape
                val isBack = e.key == Key.Escape || e.key == Key.Back
                if (openMenu != null && !isBack) return@onPreviewKeyEvent false
                // Remote buttons, and arrows: they seek while the controls are hidden, and move between buttons while shown
                if (e.type == KeyEventType.KeyDown) {
                    when (e.key) {
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { poke(); player.togglePause(); return@onPreviewKeyEvent true }
                        Key.MediaFastForward -> { poke(); player.seekBy(seekMs); return@onPreviewKeyEvent true }
                        Key.MediaRewind -> { poke(); player.seekBy(-seekMs); return@onPreviewKeyEvent true }
                        Key.MediaNext -> { model.next(); return@onPreviewKeyEvent true }
                        Key.MediaPrevious -> { model.previous(); return@onPreviewKeyEvent true }
                    }
                    if (tv) {
                        val center = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        val arrow = e.key == Key.DirectionLeft || e.key == Key.DirectionRight || e.key == Key.DirectionUp || e.key == Key.DirectionDown
                        // OK presses the Skip button while it shows over the picture, as on streaming apps
                        if (center && showSkip && !controlsVisible) {
                            model.skip(activeSkip!!)
                            return@onPreviewKeyEvent true
                        }
                        if (controlsVisible && (center || arrow)) {
                            poke()
                            return@onPreviewKeyEvent false
                        }
                        when {
                            center -> { poke(); player.togglePause(); return@onPreviewKeyEvent true }
                            e.key == Key.DirectionLeft -> { player.seekBy(-seekMs); seekFlash = -1 to System.nanoTime(); return@onPreviewKeyEvent true }
                            e.key == Key.DirectionRight -> { player.seekBy(seekMs); seekFlash = 1 to System.nanoTime(); return@onPreviewKeyEvent true }
                            arrow -> { poke(); return@onPreviewKeyEvent true }
                        }
                    }
                }
                // Space: a tap plays or pauses, holding it plays at 2x until released
                if (e.key == Key.Spacebar) {
                    when (e.type) {
                        KeyEventType.KeyDown -> { poke(); hold.press(player) }
                        KeyEventType.KeyUp -> if (!hold.release(player)) player.togglePause()
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                poke()
                when (e.key) {
                    Key.K -> player.togglePause()
                    Key.DirectionLeft, Key.J -> player.seekBy(-seekMs)
                    Key.DirectionRight, Key.L -> player.seekBy(seekMs)
                    Key.DirectionUp -> player.setVolume(playback.volume + 5)
                    Key.DirectionDown -> player.setVolume(playback.volume - 5)
                    Key.F -> toggleFullscreen()
                    Key.M -> player.toggleMute()
                    // As in VLC: A cycles the aspect ratio, Z the zoom
                    Key.A -> setFit(VideoFit.entries[(fit.ordinal + 1) % VideoFit.entries.size])
                    Key.Z -> setZoom(ZOOM_STEPS.firstOrNull { it > zoom + 0.01f } ?: 1f)
                    Key.N -> model.next()
                    Key.S -> model.activeSkip(playback.timeMs)?.let(model::skip) ?: return@onPreviewKeyEvent false
                    // Escape closes the panel first, then leaves fullscreen, then the player
                    Key.Escape, Key.Back -> when {
                        openMenu != null -> openMenu = null
                        fullscreen -> toggleFullscreen()
                        else -> exit()
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
    ) {
        if (player != null) {
            player.Video(Modifier.fillMaxSize().onSizeChanged { area = it }, fit, zoom, pan)
            Box(
                Modifier.fillMaxSize()
                    // Touch: pinch to zoom, and drag a zoomed picture around
                    .then(
                        if (touch) Modifier.pointerInput(player, settings.swipeControls) {
                            val swipes = settings.swipeControls
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val brightnessSide = down.position.x < size.width / 2
                                // 0: not decided yet, 1: brightness or volume swipe, 2: pinch or pan
                                var mode = 0
                                var moved = Offset.Zero
                                var value = 0f
                                do {
                                    val event = awaitPointerEvent()
                                    val fingers = event.changes.count { it.pressed }
                                    if (mode != 1 && (fingers >= 2 || zoom > 1f)) {
                                        // Pinch to zoom, and drag a zoomed picture around
                                        val zoomChange = event.calculateZoom()
                                        val change = event.calculatePan()
                                        if (zoomChange != 1f || change != Offset.Zero) {
                                            mode = 2
                                            // A pinch or drag isn't a hold for 2x
                                            hold.release(player)
                                            setZoom(zoom * zoomChange, announce = false)
                                            pan = clampPan(pan + change, zoom)
                                            event.changes.forEach { it.consume() }
                                        }
                                    } else if (mode != 2 && swipes) {
                                        val change = event.calculatePan()
                                        moved += change
                                        if (mode == 0 && kotlin.math.abs(moved.y) > viewConfiguration.touchSlop &&
                                            kotlin.math.abs(moved.y) > kotlin.math.abs(moved.x) * 1.5f
                                        ) {
                                            val start = if (brightnessSide) DeviceControls.brightness() else DeviceControls.volume()
                                            if (start != null) {
                                                mode = 1
                                                value = start
                                                hold.release(player)
                                            }
                                        }
                                        if (mode == 1) {
                                            // A full-height swipe goes from nothing to full
                                            value = (value - change.y / size.height * 1.2f).coerceIn(0f, 1f)
                                            if (brightnessSide) DeviceControls.setBrightness(value) else DeviceControls.setVolume(value)
                                            level = brightnessSide to value
                                            levelShownAt = System.nanoTime()
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        } else Modifier
                    )
                    .pointerInput(player, touch, seekMs) {
                    if (touch) {
                        // Touch: a tap shows or hides the controls, a double tap on either side seeks,
                        // holding plays at 2x until released
                        detectTapGestures(
                            onTap = { if (controlsVisible) controlsVisible = false else poke() },
                            onDoubleTap = { at ->
                                val forward = at.x > size.width / 2
                                player.seekBy(if (forward) seekMs else -seekMs)
                                seekFlash = (if (forward) 1 else -1) to System.nanoTime()
                            },
                            onLongPress = { },
                            onPress = {
                                hold.press(player)
                                tryAwaitRelease()
                                hold.release(player)
                            },
                        )
                    } else {
                        // A click plays or pauses, holding the mouse down plays at 2x until released
                        detectTapGestures(onPress = {
                            poke()
                            hold.press(player)
                            tryAwaitRelease()
                            if (!hold.release(player)) player.togglePause()
                        })
                    }
                },
            )
        }

        pictureFlash?.let { (label, _) ->
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // While pinching, the zoom level
        if (touch && zoom > 1f && pictureFlash == null && controlsVisible) {
            Text(
                "Zoom ${(zoom * 100).roundToInt()}%",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
                    .clickable { setZoom(1f) }
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        level?.let { (brightness, value) ->
            LevelIndicator(
                icon = when {
                    brightness -> Icons.Default.BrightnessMedium
                    value == 0f -> Icons.AutoMirrored.Filled.VolumeOff
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                value = value,
                // Next to the side being swiped
                modifier = Modifier.align(if (brightness) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 40.dp),
            )
        }

        seekFlash?.let { (direction, _) ->
            Box(
                Modifier.align(if (direction > 0) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 64.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .padding(18.dp),
            ) {
                Icon(if (direction > 0) Icons.Default.Forward10 else Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }

        if (hold.boosting) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("2x", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        // Center status: loading links, buffering or errors, on a dark panel so it reads over any picture
        val showsStatus = player == null || ui.error != null || (ui.current == null && ui.loadingLinks) ||
            (playback.buffering && ui.current != null)
        Box(
            Modifier.align(Alignment.Center).then(
                if (showsStatus) Modifier
                    .padding(24.dp)
                    .widthIn(max = 520.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 22.dp)
                else Modifier
            ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                player == null -> NoVlc(ui, model)
                ui.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.error!!, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (ui.links.isNotEmpty()) OutlinedButton(onClick = { openMenu = "panel:Sources" }) { Text("Choose a source") }
                        Button(onClick = { model.reload() }) { Text("Reload links") }
                    }
                }

                ui.current == null && ui.loadingLinks -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (ui.links.isEmpty()) "Finding sources…" else "Found ${ui.links.size} sources, still looking…",
                        color = Color.White,
                    )
                    if (ui.links.isNotEmpty()) {
                        // Skip the wait: play the best source so far, the rest keep loading
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = model::playNow) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Play now")
                            }
                            OutlinedButton(onClick = { openMenu = "panel:Sources" }) { Text("Choose a source", color = Color.White) }
                        }
                    }
                }

                playback.buffering && ui.current != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LaunchedEffect(ui.current) {
                        slowSeconds = 0
                        slowSecondsAny = 0
                        while (true) {
                            delay(1_000)
                            slowSecondsAny++
                            val crawling = ui.offlineFile == null && StreamGateway.stats.value.bytesPerSecond < SLOW_SOURCE_BYTES
                            slowSeconds = if (crawling) slowSeconds + 1 else 0
                        }
                    }
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (playback.bufferPercent > 0f) "Buffering ${playback.bufferPercent.toInt()}%" else "Loading video…",
                        color = Color.White,
                    )
                    // Speed, connections and what's fetched ahead, so a slow host is visible
                    if (ui.offlineFile == null) {
                        statsLine(stats)?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // How much the player needs before it can start, from the file's bitrate and VLC's buffer.
                    // When that is already in memory and it still waits, the hold-up isn't the network.
                    val needed = if (ui.offlineFile == null && playback.lengthMs > 0 && stats.streamSize > 0) {
                        stats.streamSize * player.startBufferMs / playback.lengthMs
                    } else null
                    if (needed != null) {
                        val ready = stats.aheadBytes >= needed
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (ready && slowSecondsAny >= 5) "Has the ${formatBytes(needed)} it needs to start, the player is still preparing it"
                            else "Needs about ${formatBytes(needed)} to start" +
                                if (stats.aheadBytes > 0) ", ${formatBytes(minOf(stats.aheadBytes, needed))} ready" else "",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                    // Stuck at a crawl: say so, and offer the other sources
                    if (slowSeconds >= 10 && ui.links.size > 1) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "This source is very slow right now. Another one may play sooner.",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 420.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { openMenu = "panel:Sources" }) { Text("Try another source", color = Color.White) }
                    }
                }
            }
        }

        AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::exit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(model.request.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val ep = ui.episodes[ui.index]
                        if (ep.season != null || ep.episode != null || ep.name != null) {
                            Text(ep.label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (ui.offlineFile == null && ui.current != null) {
                        statsLine(stats)?.let {
                            Text(
                                it,
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                    ui.current?.let { i ->
                        val label = if (ui.offlineFile != null) "Downloaded file" else ui.links.getOrNull(i)?.info()?.summary ?: ""
                        // Short summary, the full source details are in the Sources panel
                        Text(
                            label,
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 420.dp).clickable { openMenu = "panel:Sources" }.padding(8.dp),
                        )
                    }
                }

                if (player != null) {
                    BottomControls(
                        model = model,
                        preview = preview,
                        ui = ui,
                        playback = playback,
                        seekMs = seekMs,
                        fullscreen = fullscreen,
                        openMenu = openMenu,
                        onMenu = { openMenu = it },
                        onFullscreen = ::toggleFullscreen,
                        fit = fit,
                        zoom = zoom,
                        onFit = ::setFit,
                        onZoom = { setZoom(it) },
                        canFullscreen = window != null,
                        playFocus = playFocus,
                        onActive = ::poke,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                // Touch: big play, back and forward buttons in the middle, like phone video apps
                if (player != null && touch && !showsStatus && openMenu == null) {
                    Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CenterButton(Icons.Default.Replay10, "Back ${seekMs / 1000}s", 36) { poke(); player.seekBy(-seekMs) }
                        CenterButton(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", 52) { poke(); player.togglePause() }
                        CenterButton(Icons.Default.Forward10, "Forward ${seekMs / 1000}s", 36) { poke(); player.seekBy(seekMs) }
                    }
                }
            }
        }

        // "Skip Intro" button while the playhead is inside a section from the skip databases
        AnimatedVisibility(
            visible = showSkip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 136.dp),
        ) {
            val stamp = activeSkip ?: return@AnimatedVisibility
            SkipButton(if (stamp.skipToNextEpisode && model.hasNext) "Next Episode" else "Skip ${stamp.stamp.type.label}") { model.skip(stamp) }
        }

        // Sources, subtitles and audio, in a side panel instead of long menus
        openMenu?.takeIf { it.startsWith("panel:") }?.let { id ->
            val tab = PanelTab.entries.firstOrNull { it.name == id.removePrefix("panel:") } ?: PanelTab.Sources
            PlayerPanel(
                tab = tab,
                onTab = { openMenu = "panel:${it.name}" },
                ui = ui,
                playback = playback,
                model = model,
                onClose = { openMenu = null },
            )
        }
    }
}

@Composable
private fun BottomControls(
    model: PlayerModel,
    preview: SeekPreview?,
    ui: PlayerUiState,
    playback: PlaybackState,
    seekMs: Long,
    fullscreen: Boolean,
    openMenu: String?,
    onMenu: (String?) -> Unit,
    onFullscreen: () -> Unit,
    fit: VideoFit,
    zoom: Float,
    onFit: (VideoFit) -> Unit,
    onZoom: (Float) -> Unit,
    canFullscreen: Boolean,
    playFocus: FocusRequester,
    onActive: () -> Unit,
    modifier: Modifier,
) {
    val player = model.player ?: return
    val touch = Platform.isTouch
    val tv = Platform.isTv
    var dragging by remember { mutableStateOf(false) }
    // A finger or mouse held still on the seek bar mustn't let the controls hide mid-drag (the
    // seek would be lost with them)
    LaunchedEffect(dragging) {
        while (dragging) {
            onActive()
            delay(1_000)
        }
    }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val length = playback.lengthMs.coerceAtLeast(1)
    val shown = if (dragging) dragValue else playback.timeMs.toFloat() / length

    Column(modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerTime(formatTime(if (dragging) (dragValue * length).toLong() else playback.timeMs), playback.lengthMs, alignEnd = true)
            SeekBar(
                value = shown.coerceIn(0f, 1f),
                lengthMs = playback.lengthMs,
                dragging = dragging,
                preview = preview,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    player.seekTo((dragValue * length).toLong())
                    dragging = false
                },
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            PlayerTime(if (playback.lengthMs > 0) formatTime(playback.lengthMs) else "Live", playback.lengthMs, alignEnd = false)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlButton(Icons.Default.SkipPrevious, "Previous episode", enabled = model.hasPrevious) { model.previous() }
            // Touch screens have these in the middle of the picture
            if (!touch) ControlButton(Icons.Default.Replay10, "Back ${seekMs / 1000}s") { player.seekBy(-seekMs) }
            if (!touch) {
                ControlButton(
                    if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", size = 40,
                    modifier = Modifier.focusRequester(playFocus),
                ) { player.togglePause() }
            }
            if (!touch) ControlButton(Icons.Default.Forward10, "Forward ${seekMs / 1000}s") { player.seekBy(seekMs) }
            ControlButton(Icons.Default.SkipNext, "Next episode", enabled = model.hasNext) { model.next() }
            if (ui.episodes.size > 1) {
                // Same as the Android player's "Skip OP" button
                ControlButton(Icons.Default.FastForward, "Skip opening (85 seconds)") { model.skipOpening() }
            }

            Spacer(Modifier.width(12.dp))
            ControlButton(if (playback.muted || playback.volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Mute") {
                player.toggleMute()
            }
            // Phones and TVs have volume buttons
            if (!touch && !tv) {
                SlimSlider(
                    value = playback.volume / 200f,
                    onValueChange = { player.setVolume((it * 200).toInt()) },
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    "${playback.volume}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            Menu("speed", openMenu, onMenu, Icons.Default.Speed, "Speed ${playback.rate}x", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { r ->
                "${r}x" to (r == playback.rate) to { player.setRate(r) }
            })
            Menu("fit", openMenu, onMenu, Icons.Default.AspectRatio, "Picture: ${fit.label}", VideoFit.entries.map { f ->
                f.label to (f == fit) to { onFit(f) }
            })
            Menu("zoom", openMenu, onMenu, if (zoom > 1f) Icons.Default.ZoomIn else Icons.Default.ZoomOutMap, "Zoom ${(zoom * 100).roundToInt()}%", ZOOM_STEPS.map { z ->
                "${(z * 100).roundToInt()}%" to (kotlin.math.abs(z - zoom) < 0.01f) to { onZoom(z) }
            })
            ControlButton(Icons.Default.VideoLibrary, "Sources (${ui.links.size})", enabled = ui.links.isNotEmpty()) { onMenu("panel:Sources") }
            ControlButton(Icons.Default.Subtitles, "Subtitles") { onMenu("panel:Subtitles") }
            ControlButton(Icons.Default.Audiotrack, "Audio") { onMenu("panel:Audio") }
            if (canFullscreen) ControlButton(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen", onClick = onFullscreen)
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    size: Int = 28,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        // A remote has no pointer: the focused button gets a light circle behind it
        modifier = modifier.background(if (focused && Platform.isTv) Color.White.copy(alpha = 0.28f) else Color.Transparent, CircleShape),
    ) {
        Icon(icon, description, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(size.dp))
    }
}

/** The big round buttons in the middle of the picture on touch screens. */
@Composable
private fun CenterButton(icon: ImageVector, description: String, size: Int, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size((size + 28).dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(size.dp))
    }
}

/** A control-bar button that opens a list of choices. Each entry is (label to selected) to action. */
@Composable
private fun Menu(
    id: String,
    openMenu: String?,
    onMenu: (String?) -> Unit,
    icon: ImageVector,
    description: String,
    entries: List<Pair<Pair<String, Boolean>, () -> Unit>>,
) {
    Box {
        ControlButton(icon, description, enabled = entries.isNotEmpty()) { onMenu(id) }
        DropdownMenu(expanded = openMenu == id, onDismissRequest = { onMenu(null) }) {
            Text(description, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            entries.forEach { (labelSelected, action) ->
                val (label, selected) = labelSelected
                DropdownMenuItem(
                    text = { Text(label, fontWeight = if (selected) FontWeight.Bold else null) },
                    onClick = { onMenu(null); action() },
                )
            }
        }
    }
}

@Composable
private fun NoVlc(ui: PlayerUiState, model: PlayerModel) {
    val missing = Platform.playerMissing
    Column(
        Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(missing?.title ?: "The video player could not start", color = Color.White, style = MaterialTheme.typography.titleLarge)
        if (missing != null) {
            Spacer(Modifier.height(8.dp))
            Text(missing.message, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { Platform.openUrl(missing.url) }) { Text(missing.action) }
        }
        if (ui.links.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("${ui.links.size} links found for ${model.episode.label}", color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/**
 * Hold to watch at 2x, like YouTube. Pressing starts a short timer; if the press is still held
 * when it ends (and the video is playing), the speed goes to 2x until release, then back.
 */
internal class SpeedHold(private val scope: CoroutineScope) {
    var boosting by mutableStateOf(false)
        private set
    private var pressed = false
    private var timer: Job? = null
    private var savedRate = 1f

    fun press(player: VideoEngine) {
        if (pressed) return // key repeat while held
        pressed = true
        timer = scope.launch {
            delay(350)
            if (pressed && player.state.value.playing) {
                savedRate = player.state.value.rate
                player.setRate(2f)
                boosting = true
            }
        }
    }

    /** Ends the press. Returns true if it was a hold (speed was raised), false for a tap. */
    fun release(player: VideoEngine): Boolean {
        if (!pressed) return false
        pressed = false
        timer?.cancel()
        if (!boosting) return false
        player.setRate(savedRate)
        boosting = false
        return true
    }
}

/**
 * The seek bar, drawn like streaming apps': a thin track that thickens under the pointer, the
 * played part in the accent colour, the hovered stretch lightly filled, and a round handle.
 * Hovering or dragging shows a card above the pointer with the frame at that time (from the
 * preview player) and the time over it. Without a frame (Android, or before the first one
 * loads) only the time shows.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SeekBar(
    value: Float,
    lengthMs: Long,
    dragging: Boolean,
    preview: SeekPreview?,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier,
) {
    var hover by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    // The slider's track starts and ends inset by half the handle's width
    val insetPx = with(density) { (SEEK_HANDLE / 2).toPx() }
    val fraction = if (dragging) value else hover
    val previewTime = fraction?.let { (it * lengthMs).toLong() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val active = fraction != null || focused

    LaunchedEffect(previewTime?.div(SeekPreview.BUCKET_MS), preview) {
        if (previewTime != null && lengthMs > 0) preview?.request(previewTime)
    }

    Box(
        modifier
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            // Initial pass: the slider underneath handles these events too, so watch them first
            .onPointerEvents(PointerEventPass.Initial) { e ->
                when (e.type) {
                    PointerEventType.Move, PointerEventType.Enter -> {
                        // Touch has no hover: only mouse pointers show the preview without dragging
                        if (e.changes.first().type == androidx.compose.ui.input.pointer.PointerType.Mouse) {
                            val x = e.changes.first().position.x
                            hover = ((x - insetPx) / (widthPx - 2 * insetPx)).coerceIn(0f, 1f)
                        }
                    }
                    PointerEventType.Exit -> hover = null
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        SlimSlider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            enabled = lengthMs > 0,
            interaction = interaction,
            active = active,
            // Where the pointer is, ahead of the playhead: how far a click would jump
            aheadTo = hover?.takeIf { !dragging && it > value },
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(
            visible = fraction != null && previewTime != null && lengthMs > 0,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
        ) {
            val at = fraction ?: 0f
            val time = previewTime ?: 0L
            val thumbnail = preview?.thumbnail
            val gapPx = with(density) { 14.dp.roundToPx() }
            Box(
                Modifier.layout { measurable, constraints ->
                    // Sized by its content, placed centred over the pointer and above the bar,
                    // kept inside the bar's width
                    val card = measurable.measure(Constraints())
                    val center = insetPx + at * (widthPx - 2 * insetPx)
                    val left = (center - card.width / 2f).coerceIn(0f, (widthPx - card.width).toFloat().coerceAtLeast(0f))
                    layout(constraints.minWidth, 0) {
                        card.place(left.roundToInt(), -card.height - gapPx)
                    }
                },
            ) {
                if (thumbnail != null) {
                    val cardWidth = if (Platform.isTv) 288.dp else 240.dp
                    // The frame's own shape, so wide films aren't cropped (within reason)
                    val ratio = (thumbnail.width.toFloat() / thumbnail.height.coerceAtLeast(1)).coerceIn(4f / 3f, 2.4f)
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        Modifier.width(cardWidth).aspectRatio(ratio)
                            .shadow(12.dp, shape)
                            .clip(shape)
                            .background(Color.Black)
                            .border(1.5.dp, Color.White.copy(alpha = 0.85f), shape),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Image(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        // The time over the bottom of the frame, on a fade so it reads on any picture
                        Box(
                            Modifier.fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                                .padding(top = 18.dp, bottom = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            SeekTime(time)
                        }
                    }
                } else {
                    Box(
                        Modifier.shadow(8.dp, RoundedCornerShape(8.dp))
                            .background(Color(0xE6101014), RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        SeekTime(time)
                    }
                }
            }
        }
    }
}

/** Room for the seek handle at its largest; the track is inset by half of it at each end. */
private val SEEK_HANDLE = 20.dp

/**
 * The player's slider look, for seeking and volume: a thin track that thickens while [active]
 * or hovered, the value in the accent colour, an optional lighter stretch up to [aheadTo], and a
 * round white handle (ringed in the accent colour when a remote focuses it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlimSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    active: Boolean = false,
    aheadTo: Float? = null,
) {
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val big = active || focused || hovered || dragged
    val accent = MaterialTheme.colorScheme.primary
    val trackHeight by animateDpAsState(if (big) 6.dp else 4.dp)
    val handleSize by animateDpAsState(if (big) 16.dp else 12.dp)
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        interactionSource = interaction,
        thumb = {
            Box(Modifier.size(SEEK_HANDLE), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(handleSize)
                        .shadow(3.dp, CircleShape)
                        .background(Color.White, CircleShape)
                        // On a TV the handle shows it has focus, since the remote's arrows move it
                        .then(if (focused) Modifier.border(3.dp, accent, CircleShape) else Modifier),
                )
            }
        },
        track = {
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val h = trackHeight.toPx()
                val y = size.height / 2
                val radius = CornerRadius(h / 2)
                fun bar(from: Float, to: Float, color: Color) {
                    if (to <= from) return
                    drawRoundRect(color, Offset(size.width * from, y - h / 2), Size(size.width * (to - from), h), radius)
                }
                bar(0f, 1f, Color.White.copy(alpha = 0.28f))
                aheadTo?.let { bar(value, it, Color.White.copy(alpha = 0.45f)) }
                bar(0f, value, accent)
            }
        },
        modifier = modifier.hoverable(interaction),
    )
}

/**
 * A time beside the seek bar. Digits are equally wide and the label keeps the width of the
 * longest time [lengthMs] can show, so the bar doesn't shift as the seconds tick.
 */
@Composable
private fun PlayerTime(text: String, lengthMs: Long, alignEnd: Boolean) {
    val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // "2:36:13" -> "0:00:00": the widest a time of this length gets, with tabular digits
    val widest = formatTime(lengthMs.coerceAtLeast(0)).replace(Regex("[0-9]"), "0").let { if (it.length < 4) "00:00" else it }
    val width = with(density) { measurer.measure(widest, style).size.width.toDp() }
    Text(
        text,
        color = Color.White,
        style = style,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        maxLines = 1,
        modifier = Modifier.widthIn(min = width),
    )
}

@Composable
private fun SeekTime(timeMs: Long) {
    Text(
        formatTime(timeMs),
        color = Color.White,
        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SkipButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        contentColor = Color.Black,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(20.dp))
        }
    }
}

/** "8.2 MB/s · 16 connections · 48 MB ahead", or null before anything has been fetched. */
private fun statsLine(stats: StreamStats): String? {
    if (stats.totalBytes == 0L) return null
    return listOfNotNull(
        "${formatBytes(stats.bytesPerSecond)}/s",
        stats.connections.takeIf { it > 0 }?.let { if (it == 1) "1 connection" else "$it connections" },
        stats.aheadBytes.takeIf { it > 0 }?.let { "${formatBytes(it)} ahead" },
    ).joinToString(" · ")
}

/** Below this while buffering, for 10 seconds, the player suggests another source. */
private const val SLOW_SOURCE_BYTES = 200L * 1024

/** Zoom levels in the menu and for the Z key. */
private val ZOOM_STEPS = listOf(1f, 1.25f, 1.5f, 2f, 3f)

/** The level bar shown while swiping for brightness or volume. */
@Composable
private fun LevelIndicator(icon: ImageVector, value: Float, modifier: Modifier) {
    Column(
        modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp)).padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${(value * 100).roundToInt()}", color = Color.White, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.width(6.dp).height(140.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp)), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f)).background(Color.White, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.height(10.dp))
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
