package com.lagradost.cloudstream4.desktop.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.MediaSlaveType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer

/**
 * Plays video with the libVLC that ships inside VLC.app.
 *
 * Frames are rendered into memory through vlcj's callback surface and drawn by Compose,
 * because embedding a native VLC view into a Compose window does not work on macOS.
 */
class VlcPlayer private constructor(private val factory: MediaPlayerFactory) : VideoEngine {
    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    /** The latest decoded frame. Compose redraws whenever this changes. */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    private val _state = MutableStateFlow(PlaybackState())

    // While a video plays, no screensaver or display sleep. After _state, which it reads
    private val keepAwake = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
        _state.map { it.playing && !it.ended && it.error == null }.distinctUntilChanged().collect { ScreenAwake.set(it) }
    }
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // Two bitmaps so the one being drawn is never the one being written
    private val bitmaps = arrayOf(Bitmap(), Bitmap())
    private var current = 0
    private var info: ImageInfo? = null
    private var pixels = ByteArray(0)
    /** The video's real size, without decoder padding. */
    @Volatile
    private var visibleSize: java.awt.Dimension? = null

    init {
        player.videoSurface().set(
            factory.videoSurfaces().newVideoSurface(
                object : BufferFormatCallback {
                    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                        visibleSize = null
                        info = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
                        pixels = ByteArray(sourceWidth * sourceHeight * 4)
                        bitmaps.forEach { it.allocPixels(info!!) }
                        return RV32BufferFormat(sourceWidth, sourceHeight)
                    }

                    override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit
                    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
                },
                object : RenderCallback {
                    override fun lock(mediaPlayer: MediaPlayer) = Unit
                    override fun unlock(mediaPlayer: MediaPlayer) = Unit
                    override fun display(
                        mediaPlayer: MediaPlayer,
                        nativeBuffers: Array<out ByteBuffer>,
                        bufferFormat: BufferFormat,
                        displayWidth: Int,
                        displayHeight: Int,
                    ) {
                        val imageInfo = info ?: return
                        val buffer = nativeBuffers[0]
                        if (buffer.remaining() < pixels.size) return
                        buffer.get(pixels)
                        buffer.rewind()
                        current = 1 - current
                        val bitmap = bitmaps[current]
                        // FFmpeg pads decoded pictures (a 720-line H.264 video arrives as 738 lines) and VLC
                        // passes the padded size on. Show only the video's real size, asked once per format.
                        val size = visibleSize ?: mediaPlayer.video().videoDimension()?.also { visibleSize = it }
                        val w = size?.width?.takeIf { it in 1..imageInfo.width } ?: imageInfo.width
                        val h = size?.height?.takeIf { it in 1..imageInfo.height } ?: imageInfo.height
                        val visible = if (w < imageInfo.width || h < imageInfo.height) {
                            ImageInfo(w, h, imageInfo.colorType, imageInfo.colorAlphaType)
                        } else imageInfo
                        bitmap.installPixels(visible, pixels, imageInfo.minRowBytes)
                        // Not the start of the video shown before a resume jump (see open)
                        if (!jumping) frame = bitmap.asComposeImageBitmap()
                    }
                },
                true,
            )
        )

        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) { VlcLog.log("event opening") }
            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) { VlcLog.log("event video outputs: $newCount") }
            override fun mediaPlayerReady(mediaPlayer: MediaPlayer) { VlcLog.log("event ready") }

            override fun playing(mediaPlayer: MediaPlayer) {
                VlcLog.log("event playing")
                // "Playing" comes as soon as the input opens, often seconds before any picture: the
                // loader stays until the first time update (timeChanged) clears it
                _state.update { it.copy(playing = true, buffering = it.buffering || jumping, ended = false, error = null) }
            }

            override fun paused(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = false) }
            override fun stopped(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = false) }
            override fun finished(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = false, ended = true) }
            override fun error(mediaPlayer: MediaPlayer) {
                VlcLog.log("event error")
                onError()
            }

            private fun onError() =
                _state.update { it.copy(playing = false, buffering = false, error = "This source could not be played.") }

            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) =
                _state.update { it.copy(buffering = jumping || newCache < 100f, bufferPercent = newCache) }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                // Live: nothing to resume, show it as it comes (a saved position from an older
                // version would otherwise keep the picture hidden for good)
                if (jumping && currentUrl?.let { StreamProxy.isLive(it) } == true) {
                    pendingJumpMs = null
                    landingMs = null
                    VlcLog.log("event live, no jump")
                    mediaPlayer.submit { player.audio().setMute(_state.value.muted) }
                }
                pendingJumpMs?.let { target ->
                    // A second played from 0: jump to the resume point (off VLC's event thread).
                    // Jumping at the first time event, as playback became ready, stalled VLC
                    if (newTime >= 1_000) {
                        pendingJumpMs = null
                        landingMs = target
                        VlcLog.log("event jump to $target ms")
                        mediaPlayer.submit { seekTo(target) }
                    }
                    return
                }
                landingMs?.let { target ->
                    // Times from before the jump, or the target itself (VLC reports it the moment it
                    // jumps): not landed yet, keep showing the resume point
                    if (newTime == target || newTime < target - 3_000) return
                    landingMs = null
                    VlcLog.log("event landed at $newTime ms")
                    mediaPlayer.submit { player.audio().setMute(_state.value.muted) }
                }
                if (_state.value.timeMs == 0L && newTime > 0) VlcLog.log("event first frame time: $newTime ms")
                _state.update { it.copy(timeMs = newTime, buffering = false, live = currentUrl?.let { u -> StreamProxy.isLive(u) } == true) }
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) =
                _state.update { it.copy(lengthMs = newLength) }

            override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) {
                VlcLog.log("event track added: $type $id")
                refreshTracks()
            }

            override fun elementaryStreamDeleted(mediaPlayer: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) =
                refreshTracks()

            override fun elementaryStreamSelected(mediaPlayer: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) =
                refreshTracks()
        })
    }

    private fun refreshTracks() = _state.update {
        it.copy(
            audioTracks = player.audio().trackDescriptions().map { t -> Track(t.id(), t.description()) }.toPersistentList(),
            subtitleTracks = player.subpictures().trackDescriptions().map { t -> Track(t.id(), t.description()) }.toPersistentList(),
            audioTrack = player.audio().track(),
            subtitleTrack = player.subpictures().track(),
        )
    }

    override val startBufferMs: Long get() = NETWORK_CACHING_MS

    /** VLC only takes headers through the proxy, which [StreamGateway] already set up. */
    override fun open(source: StreamSource, startMs: Long, subtitles: List<String>) {
        val url = source.url
        VlcLog.log("open ${if (url.startsWith("http://127.0.0.1")) "proxied stream" else url.substringBefore(':')} at $startMs ms")
        frame = null
        // HLS started mid-way showed no picture at all (Prime Video, audio in its own playlist):
        // VLC's clock began minutes early and dropped every frame. Start at 0, jump once it runs
        val jumpLater = (source.mimeType == StreamSource.HLS || source.mimeType == StreamSource.DASH) && startMs > 0
        pendingJumpMs = if (jumpLater) startMs else null
        landingMs = null
        // So the proxy fetches the segments there while the start plays, not after the jump
        if (jumpLater) StreamProxy.resumeAt(url, startMs)
        // The speed carries over to the next video, as the player itself keeps it. While a jump
        // waits, the time shown (and saved as progress) is where it will land
        _state.update { PlaybackState(volume = it.volume, muted = it.muted, rate = it.rate, buffering = true, timeMs = pendingJumpMs ?: 0L) }
        val options = buildList {
            add(":network-caching=$NETWORK_CACHING_MS")
            if (startMs > 0 && !jumpLater) add(":start-time=${startMs / 1000.0}")
        }
        player.media().play(url, *options.toTypedArray())
        // Silent until the resume jump lands; the user's mute setting comes back then
        if (jumpLater) player.audio().setMute(true)
        currentUrl = url
        // External subtitles are added but not selected, the user picks one
        subtitles.forEach { player.media().addSlave(MediaSlaveType.SUBTITLE, it, false) }
        player.audio().setVolume(_state.value.volume)
        if (_state.value.rate != 1f) player.controls().setRate(_state.value.rate)
    }

    override fun togglePause() {
        if (_state.value.ended) {
            player.controls().setTime(0)
            player.controls().play()
        } else {
            player.controls().pause()
        }
    }

    override fun seekTo(ms: Long) {
        val length = _state.value.lengthMs
        val target = if (length > 0) ms.coerceIn(0, length) else ms.coerceAtLeast(0)
        // After the end libVLC has stopped the input and ignores jumps: start it again first
        if (_state.value.ended) player.controls().play()
        player.controls().setTime(target)
        _state.update { it.copy(timeMs = target, ended = false) }
    }

    override fun seekBy(ms: Long) = seekTo(_state.value.timeMs + ms)

    override fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 200)
        player.audio().setVolume(v)
        // Changing the volume unmutes, as the icon shows; VLC keeps mute apart from the volume,
        // so without this the sound stayed off and the next mute press muted it again
        if (_state.value.muted) player.audio().setMute(false)
        _state.update { it.copy(volume = v, muted = false) }
    }

    override fun toggleMute() {
        val muted = !_state.value.muted
        player.audio().setMute(muted)
        _state.update { it.copy(muted = muted) }
    }

    override fun setRate(rate: Float) {
        player.controls().setRate(rate)
        _state.update { it.copy(rate = rate) }
    }

    override fun setAudioTrack(id: Int) {
        player.audio().setTrack(id)
        refreshTracks()
    }

    /** Adds a subtitle file (a local file uri) and shows it. */
    override fun addSubtitle(uri: String) {
        VlcLog.log("add subtitle file")
        player.media().addSlave(MediaSlaveType.SUBTITLE, uri, true)
    }

    override fun setSubtitleTrack(id: Int) {
        player.subpictures().setTrack(id)
        refreshTracks()
    }

    /** The url currently opened, so seek bar previews can open the same video. */
    override var currentUrl by mutableStateOf<String?>(null)
        private set

    /** Where an HLS stream opened at 0 jumps once it plays (see [open]). */
    @Volatile
    private var pendingJumpMs: Long? = null

    /** Where that jump lands, until VLC's time gets there. */
    @Volatile
    private var landingMs: Long? = null

    /** Before a resume jump has landed: no picture or sound from the start, the loader stays up. */
    private val jumping get() = pendingJumpMs != null || landingMs != null

    /** A silent second player for seek bar thumbnails. Release it when done. */
    override fun createPreview(): SeekPreview = VlcSeekPreview(factory)

    override fun captureFrame(): ImageBitmap? = frame

    @Composable
    override fun Video(modifier: Modifier, fit: VideoFit, zoom: Float, pan: Offset) {
        val shown = frame ?: return
        // VLC draws subtitles into the frame, so they zoom with the picture
        val zoomed = Modifier.graphicsLayer {
            scaleX = zoom
            scaleY = zoom
            translationX = pan.x
            translationY = pan.y
        }
        val ratio = fit.ratio
        if (ratio != null) {
            Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
                Image(shown, null, contentScale = ContentScale.FillBounds, modifier = Modifier.aspectRatio(ratio).then(zoomed))
            }
        } else {
            val scale = when (fit) {
                VideoFit.Crop -> ContentScale.Crop
                VideoFit.Stretch -> ContentScale.FillBounds
                else -> ContentScale.Fit
            }
            Image(shown, null, contentScale = scale, modifier = modifier.clipToBounds().then(zoomed))
        }
    }

    override fun stop() {
        player.controls().stop()
        frame = null
        currentUrl = null
        _state.update { PlaybackState(volume = it.volume, muted = it.muted, rate = it.rate, buffering = false) }
    }

    override fun release() {
        keepAwake.cancel()
        ScreenAwake.set(false)
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        runCatching { factory.release() }
    }

    companion object {
        /**
         * FFmpeg decoding first, without hardware decoding. With VideoToolbox, frames arrive as
         * CoreVideo buffers that VLC can't blend subtitles into ("no matching alpha blending
         * routine YUVA -> CVPN"), so subtitles never showed in the frames drawn by the app.
         * "all" keeps every other decoder as a fallback, for audio and subtitle formats.
         */
        private val FACTORY_ARGS = arrayOf("--no-video-title-show", "--codec=avcodec,all", "--avcodec-hw=none")

        /** How much video VLC buffers before it starts, in milliseconds. */
        const val NETWORK_CACHING_MS = 1500L

        /** VLC's own log (warnings and errors) in logs/vlc.log, to see why a stream won't play. */
        private var nativeLog: uk.co.caprica.vlcj.log.NativeLog? = null

        internal fun attachLog(factory: MediaPlayerFactory) {
            runCatching {
                nativeLog = factory.application().newLog().apply {
                    // CLOUDSTREAM_VLC_DEBUG=1 logs everything, to see which decoder and converters VLC picks
                    level = if (System.getenv("CLOUDSTREAM_VLC_DEBUG") == "1") uk.co.caprica.vlcj.log.LogLevel.DEBUG
                    else uk.co.caprica.vlcj.log.LogLevel.WARNING
                    addLogListener { level, module, _, _, _, _, _, message ->
                        VlcLog.log("$level $module: $message")
                    }
                }
            }
        }

        /** Finds libVLC, usually inside /Applications/VLC.app. Returns null when VLC is not installed. */
        fun create(): VlcPlayer? {
            val found = runCatching { NativeDiscovery().discover() }.getOrDefault(false)
            if (!found) return null
            return runCatching {
                VlcPlayer(MediaPlayerFactory(*FACTORY_ARGS).also { attachLog(it) })
            }.onFailure { it.printStackTrace() }.getOrNull()
        }
    }
}
