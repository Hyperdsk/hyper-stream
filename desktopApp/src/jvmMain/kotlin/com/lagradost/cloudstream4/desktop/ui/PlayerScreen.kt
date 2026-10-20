package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream4.desktop.player.StreamStats
import com.lagradost.cloudstream4.desktop.player.StreamProxy
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
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.player.PlaybackState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import com.lagradost.cloudstream4.desktop.player.SeekPreview
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
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
import java.awt.Desktop
import java.net.URI

val LocalWindowState = staticCompositionLocalOf<WindowState?> { null }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(model: PlayerModel, onExit: () -> Unit) {
    val ui by model.state.collectAsState()
    val player = model.player
    val playback by (player?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(PlaybackState()) }).collectAsState()
    val settings by SettingsStore.state.collectAsState()
    val stats by StreamProxy.stats.collectAsState()
    // Seconds spent buffering at a crawl, for the "slow source" hint
    var slowSeconds by remember { mutableIntStateOf(0) }
    val window = LocalWindowState.current
    val focus = remember { FocusRequester() }
    val holdScope = rememberCoroutineScope()
    val hold = remember { SpeedHold(holdScope) }

    // Seek bar thumbnails, from a second silent player on the same video
    val preview = remember(player) { player?.createPreview() }
    DisposableEffect(preview) { onDispose { preview?.release() } }
    val currentUrl = player?.currentUrl
    // Open the preview player only once the video plays, and at low priority ("?preview"), so it
    // never competes with the main player for bandwidth while that is starting up
    val startedPlaying = playback.playing || playback.timeMs > 0
    LaunchedEffect(preview, currentUrl, startedPlaying) {
        preview?.open(currentUrl?.takeIf { startedPlaying }?.let { if (it.startsWith("http://127.0.0.1")) "$it?preview" else it })
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var lastMove by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var openMenu by remember { mutableStateOf<String?>(null) }

    val fullscreen = window?.placement == WindowPlacement.Fullscreen
    fun toggleFullscreen() {
        window?.placement = if (fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }
    fun exit() {
        if (fullscreen) window?.placement = WindowPlacement.Floating
        onExit()
    }
    fun poke() {
        lastMove = System.currentTimeMillis()
        controlsVisible = true
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
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
            .focusRequester(focus)
            .focusable()
            .onPointerEvent(PointerEventType.Move) { poke() }
            .onPreviewKeyEvent { e ->
                if (player == null) return@onPreviewKeyEvent false
                // With the side panel open, keys go to it (its filter box), except Escape
                if (openMenu != null && e.key != Key.Escape) return@onPreviewKeyEvent false
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
                    Key.N -> model.next()
                    Key.S -> model.activeSkip(playback.timeMs)?.let(model::skip) ?: return@onPreviewKeyEvent false
                    // Escape closes the panel first, then leaves fullscreen, then the player
                    Key.Escape -> when {
                        openMenu != null -> openMenu = null
                        fullscreen -> toggleFullscreen()
                        else -> exit()
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
    ) {
        val frame = player?.frame
        if (frame != null) {
            Image(
                frame,
                null,
                contentScale = ContentScale.Fit,
                // A click plays or pauses, holding the mouse down plays at 2x until released
                modifier = Modifier.fillMaxSize().pointerInput(player) {
                    detectTapGestures(onPress = {
                        poke()
                        hold.press(player)
                        tryAwaitRelease()
                        if (!hold.release(player)) player.togglePause()
                    })
                },
            )
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

        // Center status: loading links, buffering or errors
        Box(Modifier.align(Alignment.Center)) {
            when {
                player == null -> NoVlc(ui, model)
                ui.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.error!!, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (ui.links.isNotEmpty()) OutlinedButton(onClick = { openMenu = "panel:Sources" }) { Text("Choose a source") }
                        Button(onClick = { model.loadEpisode(ui.index) }) { Text("Reload links") }
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
                        while (true) {
                            delay(1_000)
                            val crawling = ui.offlineFile == null && StreamProxy.stats.value.bytesPerSecond < SLOW_SOURCE_BYTES
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
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        // "Skip Intro" button while the playhead is inside a section from the skip databases.
        // Like Android it shows for 6 seconds when the section starts, and whenever the controls show.
        val activeSkip = model.activeSkip(playback.timeMs)
        var skipFresh by remember { mutableStateOf(false) }
        LaunchedEffect(activeSkip) {
            skipFresh = activeSkip != null
            if (activeSkip != null) {
                delay(6_000)
                skipFresh = false
            }
        }
        val autoSkipping = activeSkip != null && settings.autoSkipIntros && activeSkip.stamp.type.isIntro
        AnimatedVisibility(
            visible = activeSkip != null && !autoSkipping && (skipFresh || controlsVisible) && openMenu == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 136.dp),
        ) {
            val stamp = activeSkip ?: return@AnimatedVisibility
            SkipButton(stamp.buttonText) { model.skip(stamp) }
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
    modifier: Modifier,
) {
    val player = model.player ?: return
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val length = playback.lengthMs.coerceAtLeast(1)
    val shown = if (dragging) dragValue else playback.timeMs.toFloat() / length

    Column(modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(if (dragging) (dragValue * length).toLong() else playback.timeMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
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
            Text(if (playback.lengthMs > 0) formatTime(playback.lengthMs) else "Live", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlButton(Icons.Default.SkipPrevious, "Previous episode", enabled = model.hasPrevious) { model.previous() }
            ControlButton(Icons.Default.Replay10, "Back ${seekMs / 1000}s") { player.seekBy(-seekMs) }
            ControlButton(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", size = 40) { player.togglePause() }
            ControlButton(Icons.Default.Forward10, "Forward ${seekMs / 1000}s") { player.seekBy(seekMs) }
            ControlButton(Icons.Default.SkipNext, "Next episode", enabled = model.hasNext) { model.next() }
            if (ui.episodes.size > 1) {
                // Same as the Android player's "Skip OP" button
                ControlButton(Icons.Default.FastForward, "Skip opening (85 seconds)") { model.skipOpening() }
            }

            Spacer(Modifier.width(12.dp))
            ControlButton(if (playback.muted || playback.volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Mute") {
                player.toggleMute()
            }
            Slider(
                value = playback.volume / 200f,
                onValueChange = { player.setVolume((it * 200).toInt()) },
                modifier = Modifier.width(120.dp),
            )
            Text("${playback.volume}%", color = Color.White, style = MaterialTheme.typography.labelSmall)

            Spacer(Modifier.weight(1f))

            Menu("speed", openMenu, onMenu, Icons.Default.Speed, "Speed ${playback.rate}x", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { r ->
                "${r}x" to (r == playback.rate) to { player.setRate(r) }
            })
            ControlButton(Icons.Default.VideoLibrary, "Sources (${ui.links.size})", enabled = ui.links.isNotEmpty()) { onMenu("panel:Sources") }
            ControlButton(Icons.Default.Subtitles, "Subtitles") { onMenu("panel:Subtitles") }
            ControlButton(Icons.Default.Audiotrack, "Audio") { onMenu("panel:Audio") }
            ControlButton(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen", onClick = onFullscreen)
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, description: String, enabled: Boolean = true, size: Int = 28, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, description, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(size.dp))
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
    Column(
        Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("VLC is needed to play videos", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Install VLC into your Applications folder, then restart CloudStream.",
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { runCatching { Desktop.getDesktop().browse(URI("https://www.videolan.org/vlc/")) } }) { Text("Get VLC") }
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

    fun press(player: VlcPlayer) {
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
    fun release(player: VlcPlayer): Boolean {
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
 * The seek slider with a thumbnail preview. Hovering or dragging shows the frame and time at
 * that point above the pointer, like streaming sites do.
 */
@OptIn(ExperimentalComposeUiApi::class)
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
    // The slider's track starts and ends inset by the thumb's radius
    val insetPx = with(density) { 10.dp.toPx() }
    val fraction = if (dragging) value else hover
    val previewTime = fraction?.let { (it * lengthMs).toLong() }

    LaunchedEffect(previewTime?.div(SeekPreview.BUCKET_MS), preview) {
        if (previewTime != null && lengthMs > 0) preview?.request(previewTime)
    }

    Box(
        modifier
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            // Initial pass: the slider underneath handles these events too, so watch them first
            .onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { e ->
                val x = e.changes.first().position.x
                hover = ((x - insetPx) / (widthPx - 2 * insetPx)).coerceIn(0f, 1f)
            }
            .onPointerEvent(PointerEventType.Enter, pass = PointerEventPass.Initial) { e ->
                val x = e.changes.first().position.x
                hover = ((x - insetPx) / (widthPx - 2 * insetPx)).coerceIn(0f, 1f)
            }
            .onPointerEvent(PointerEventType.Exit, pass = PointerEventPass.Initial) { hover = null },
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            enabled = lengthMs > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        if (fraction != null && previewTime != null && lengthMs > 0) {
            val cardWidth = 192.dp
            val cardWidthPx = with(density) { cardWidth.toPx() }
            val centerPx = insetPx + fraction * (widthPx - 2 * insetPx)
            val left = (centerPx - cardWidthPx / 2).coerceIn(0f, (widthPx - cardWidthPx).coerceAtLeast(0f))
            Column(
                Modifier
                    .offset { IntOffset(left.roundToInt(), -with(density) { 140.dp.roundToPx() }) }
                    // Its own size, not limited to the slider's height
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .width(cardWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val thumbnail = preview?.thumbnail
                Box(
                    Modifier.width(cardWidth).aspectRatio(16f / 9f)
                        .background(Color.Black, RoundedCornerShape(6.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .clip(RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumbnail != null) {
                        Image(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else if (preview != null) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    formatTime(previewTime),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
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
