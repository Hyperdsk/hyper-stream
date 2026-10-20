package com.lagradost.cloudstream4.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

data class Track(val id: Int, val name: String)

data class PlaybackState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    /** VLC's buffer fill while buffering, 0 to 100. */
    val bufferPercent: Float = 0f,
    val timeMs: Long = 0,
    val lengthMs: Long = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    val rate: Float = 1f,
    val ended: Boolean = false,
    val error: String? = null,
    val audioTracks: PersistentList<Track> = persistentListOf(),
    val subtitleTracks: PersistentList<Track> = persistentListOf(),
    val audioTrack: Int = -1,
    val subtitleTrack: Int = -1,
)

/**
 * Plays video with the libVLC that ships inside VLC.app.
 *
 * Frames are rendered into memory through vlcj's callback surface and drawn by Compose,
 * because embedding a native VLC view into a Compose window does not work on macOS.
 */
class VlcPlayer private constructor(private val factory: MediaPlayerFactory) {
    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    /** The latest decoded frame. Compose redraws whenever this changes. */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // Two bitmaps so the one being drawn is never the one being written
    private val bitmaps = arrayOf(Bitmap(), Bitmap())
    private var current = 0
    private var info: ImageInfo? = null
    private var pixels = ByteArray(0)

    init {
        player.videoSurface().set(
            factory.videoSurfaces().newVideoSurface(
                object : BufferFormatCallback {
                    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
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
                        bitmap.installPixels(imageInfo, pixels, imageInfo.minRowBytes)
                        frame = bitmap.asComposeImageBitmap()
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
                _state.update { it.copy(playing = true, buffering = false, ended = false, error = null) }
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
                _state.update { it.copy(buffering = newCache < 100f, bufferPercent = newCache) }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                if (_state.value.timeMs == 0L && newTime > 0) VlcLog.log("event first frame time: $newTime ms")
                _state.update { it.copy(timeMs = newTime, buffering = false) }
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

    /** @param subtitles local proxy urls of external subtitle files */
    fun open(url: String, startMs: Long = 0, subtitles: List<String> = emptyList()) {
        VlcLog.log("open ${if (url.startsWith("http://127.0.0.1")) "proxied stream" else url.substringBefore(':')} at $startMs ms")
        frame = null
        _state.update { PlaybackState(volume = it.volume, muted = it.muted, buffering = true) }
        val options = buildList {
            add(":network-caching=$NETWORK_CACHING_MS")
            if (startMs > 0) add(":start-time=${startMs / 1000.0}")
        }
        player.media().play(url, *options.toTypedArray())
        currentUrl = url
        // External subtitles are added but not selected, the user picks one
        subtitles.forEach { player.media().addSlave(MediaSlaveType.SUBTITLE, it, false) }
        player.audio().setVolume(_state.value.volume)
    }

    fun togglePause() {
        if (_state.value.ended) {
            player.controls().setTime(0)
            player.controls().play()
        } else {
            player.controls().pause()
        }
    }

    fun seekTo(ms: Long) {
        val length = _state.value.lengthMs
        val target = if (length > 0) ms.coerceIn(0, length) else ms.coerceAtLeast(0)
        player.controls().setTime(target)
        _state.update { it.copy(timeMs = target, ended = false) }
    }

    fun seekBy(ms: Long) = seekTo(_state.value.timeMs + ms)

    fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 200)
        player.audio().setVolume(v)
        _state.update { it.copy(volume = v, muted = false) }
    }

    fun toggleMute() {
        val muted = !_state.value.muted
        player.audio().setMute(muted)
        _state.update { it.copy(muted = muted) }
    }

    fun setRate(rate: Float) {
        player.controls().setRate(rate)
        _state.update { it.copy(rate = rate) }
    }

    fun setAudioTrack(id: Int) {
        player.audio().setTrack(id)
        refreshTracks()
    }

    /** Adds a subtitle file (a local file uri) and shows it. */
    fun addSubtitle(uri: String) {
        VlcLog.log("add subtitle file")
        player.media().addSlave(MediaSlaveType.SUBTITLE, uri, true)
    }

    fun setSubtitleTrack(id: Int) {
        player.subpictures().setTrack(id)
        refreshTracks()
    }

    /** The url currently opened, so seek bar previews can open the same video. */
    var currentUrl by mutableStateOf<String?>(null)
        private set

    /** A silent second player for seek bar thumbnails. Release it when done. */
    fun createPreview(): SeekPreview = SeekPreview(factory)

    fun stop() {
        player.controls().stop()
        frame = null
        currentUrl = null
        _state.update { PlaybackState(volume = it.volume, muted = it.muted, buffering = false) }
    }

    fun release() {
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        runCatching { factory.release() }
    }

    companion object {
        /** How much video VLC buffers before it starts, in milliseconds. */
        const val NETWORK_CACHING_MS = 1500L

        /** VLC's own log (warnings and errors) in logs/vlc.log, to see why a stream won't play. */
        private var nativeLog: uk.co.caprica.vlcj.log.NativeLog? = null

        internal fun attachLog(factory: MediaPlayerFactory) {
            runCatching {
                nativeLog = factory.application().newLog().apply {
                    level = uk.co.caprica.vlcj.log.LogLevel.WARNING
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
                VlcPlayer(MediaPlayerFactory("--no-video-title-show").also { attachLog(it) })
            }.onFailure { it.printStackTrace() }.getOrNull()
        }
    }
}
