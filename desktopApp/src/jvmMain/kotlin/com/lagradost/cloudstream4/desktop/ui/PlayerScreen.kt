package com.lagradost.cloudstream4.desktop.ui

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
import kotlinx.coroutines.delay
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
    val window = LocalWindowState.current
    val focus = remember { FocusRequester() }

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
                if (e.type != KeyEventType.KeyDown || player == null) return@onPreviewKeyEvent false
                poke()
                when (e.key) {
                    Key.Spacebar, Key.K -> player.togglePause()
                    Key.DirectionLeft, Key.J -> player.seekBy(-seekMs)
                    Key.DirectionRight, Key.L -> player.seekBy(seekMs)
                    Key.DirectionUp -> player.setVolume(playback.volume + 5)
                    Key.DirectionDown -> player.setVolume(playback.volume - 5)
                    Key.F -> toggleFullscreen()
                    Key.M -> player.toggleMute()
                    Key.N -> model.next()
                    Key.Escape -> if (fullscreen) toggleFullscreen() else exit()
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
                modifier = Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { player.togglePause(); poke() },
            )
        }

        // Center status: loading links, buffering or errors
        Box(Modifier.align(Alignment.Center)) {
            when {
                player == null -> NoVlc(ui, model)
                ui.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.error!!, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (ui.links.isNotEmpty()) OutlinedButton(onClick = { openMenu = "sources" }) { Text("Choose a source") }
                        Button(onClick = { model.loadEpisode(ui.index) }) { Text("Reload links") }
                    }
                }

                ui.current == null && ui.loadingLinks -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (ui.links.isEmpty()) "Loading links…" else "Found ${ui.links.size} links…",
                        color = Color.White,
                    )
                }

                playback.buffering && ui.current != null -> CircularProgressIndicator(color = Color.White)
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
                        val ep = model.request.episodes[ui.index]
                        if (ep.season != null || ep.episode != null || ep.name != null) {
                            Text(ep.label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    ui.current?.let { i ->
                        val label = if (ui.offlineFile != null) "Downloaded file" else ui.links.getOrNull(i)?.label() ?: ""
                        Text(label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (player != null) {
                    BottomControls(
                        model = model,
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
    }
}

@Composable
private fun BottomControls(
    model: PlayerModel,
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
            Slider(
                value = shown.coerceIn(0f, 1f),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    player.seekTo((dragValue * length).toLong())
                    dragging = false
                },
                enabled = playback.lengthMs > 0,
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
            Menu("sources", openMenu, onMenu, Icons.Default.VideoLibrary, "Sources (${ui.links.size})", ui.links.mapIndexed { i, link ->
                (link.label() + if (i in ui.failed) " (failed)" else "") to (i == ui.current) to { model.playLink(i) }
            })
            Menu(
                "subtitles", openMenu, onMenu, Icons.Default.Subtitles, "Subtitles",
                playback.subtitleTracks.map { t -> t.name to (t.id == playback.subtitleTrack) to { player.setSubtitleTrack(t.id) } } +
                    listOfNotNull(
                        if (ui.subtitles.isNotEmpty() && playback.subtitleTracks.size <= 1) {
                            "Load ${ui.subtitles.size} provider subtitles" to false to { model.reloadSubtitlesIntoPlayer() }
                        } else null
                    ),
            )
            Menu("audio", openMenu, onMenu, Icons.Default.Audiotrack, "Audio", playback.audioTracks.map { t ->
                t.name to (t.id == playback.audioTrack) to { player.setAudioTrack(t.id) }
            })
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
