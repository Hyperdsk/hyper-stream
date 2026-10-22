@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.lagradost.cloudstream4.desktop.player

import android.content.Context
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import com.lagradost.cloudstream3.app
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Plays video with Media3 ExoPlayer, the player the CloudStream Android app uses. Links are
 * fetched through the same OkHttp client the extensions use, with each link's headers.
 * Call from the main thread only, like ExoPlayer itself.
 */
class ExoVideoEngine(private val context: Context) : VideoEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override var currentUrl by mutableStateOf<String?>(null)
        private set

    override val startBufferMs: Long = START_BUFFER_MS

    private val meter = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            if (isNetwork) StreamGateway.Meter.connections.incrementAndGet()
        }
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) StreamGateway.Meter.bytes.addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            if (isNetwork) StreamGateway.Meter.connections.decrementAndGet()
        }
    }

    private val http = OkHttpDataSource.Factory(app.baseClient).setTransferListener(meter)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 120_000, START_BUFFER_MS.toInt(), 5_000)
                .build()
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private var source: StreamSource? = null
    private val subtitles = mutableListOf<String>()
    private var surface: PlayerSurface? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = refresh()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refresh()
            override fun onTracksChanged(tracks: Tracks) = refreshTracks()
            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(error = describe(error), buffering = false, playing = false) }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                surface?.setVideoSize(videoSize)
            }
            override fun onCues(cueGroup: CueGroup) {
                surface?.subtitles?.setCues(cueGroup.cues)
            }
        })
        // The clock, four times a second
        scope.launch {
            while (true) {
                delay(250)
                if (currentUrl == null) continue
                val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
                val position = player.currentPosition.coerceAtLeast(0)
                val buffered = (player.bufferedPosition - position).coerceAtLeast(0)
                _state.update {
                    it.copy(
                        timeMs = position,
                        lengthMs = duration,
                        bufferPercent = if (it.buffering) (buffered * 100f / START_BUFFER_MS).coerceAtMost(99f) else 0f,
                    )
                }
            }
        }
    }

    /**
     * Whether the app is on screen (set by the activity). A video that opens while it's in the
     * background (links can take a while) stays paused instead of playing with nothing showing.
     */
    var inForeground = true

    private fun refresh() {
        val s = player.playbackState
        // The screen stays on only while a video plays or loads, so a paused one can sleep
        surface?.keepScreenOn = player.playWhenReady && s != Player.STATE_ENDED && s != Player.STATE_IDLE
        _state.update {
            it.copy(
                playing = player.playWhenReady && s != Player.STATE_ENDED,
                buffering = s == Player.STATE_BUFFERING || (s == Player.STATE_IDLE && currentUrl != null && it.error == null),
                ended = s == Player.STATE_ENDED,
                timeMs = player.currentPosition.coerceAtLeast(0),
            )
        }
    }

    private fun groups(type: Int) = player.currentTracks.groups.filter { it.type == type && it.isSupported }

    private fun refreshTracks() {
        val audio = groups(C.TRACK_TYPE_AUDIO)
        val text = groups(C.TRACK_TYPE_TEXT)
        _state.update {
            it.copy(
                audioTracks = audio.mapIndexed { i, g -> Track(i, describe(g.getTrackFormat(0), i)) }.toPersistentList(),
                subtitleTracks = text.mapIndexed { i, g -> Track(i, describe(g.getTrackFormat(0), i)) }.toPersistentList(),
                audioTrack = audio.indexOfFirst { g -> g.isSelected },
                subtitleTrack = text.indexOfFirst { g -> g.isSelected },
            )
        }
    }

    private fun mediaItem(source: StreamSource): MediaItem {
        val subs = subtitles.mapIndexed { i, uri ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(uri))
                .setId("external-$i")
                .setMimeType(subtitleMime(uri))
                .setLabel(uri.substringAfterLast('/').substringBeforeLast('.').take(40))
                // The newest one is the one just picked, so it shows
                .setSelectionFlags(if (i == subtitles.lastIndex && pickedSubtitle) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        return MediaItem.Builder()
            .setUri(source.url)
            .apply { source.mimeType?.let { setMimeType(if (it == StreamSource.HLS) MimeTypes.APPLICATION_M3U8 else if (it == StreamSource.DASH) MimeTypes.APPLICATION_MPD else it) } }
            .setSubtitleConfigurations(subs)
            .build()
    }

    private var pickedSubtitle = false

    override fun open(source: StreamSource, startMs: Long, subtitles: List<String>) {
        this.source = source
        this.subtitles.clear()
        this.subtitles += subtitles
        pickedSubtitle = false
        http.setDefaultRequestProperties(source.headers)
        // ExoPlayer keeps its speed for the next video: so does the state, or the menu said 1x
        _state.update { PlaybackState(volume = it.volume, muted = it.muted, rate = it.rate, buffering = true) }
        currentUrl = source.url
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        player.setMediaItem(mediaItem(source), startMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = inForeground
        applyVolume()
    }

    override fun togglePause() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.playWhenReady = true
        } else {
            player.playWhenReady = !player.playWhenReady
        }
        refresh()
    }

    override fun seekTo(ms: Long) {
        val length = _state.value.lengthMs
        val target = if (length > 0) ms.coerceIn(0, length) else ms.coerceAtLeast(0)
        player.seekTo(target)
        _state.update { it.copy(timeMs = target, ended = false) }
    }

    override fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 100)
        _state.update { it.copy(volume = v, muted = false) }
        applyVolume()
    }

    override fun toggleMute() {
        _state.update { it.copy(muted = !it.muted) }
        applyVolume()
    }

    private fun applyVolume() {
        val s = _state.value
        player.volume = if (s.muted) 0f else s.volume / 100f
    }

    override fun setRate(rate: Float) {
        player.setPlaybackSpeed(rate)
        _state.update { it.copy(rate = rate) }
    }

    override fun setAudioTrack(id: Int) {
        val group = groups(C.TRACK_TYPE_AUDIO).getOrNull(id) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    /** ExoPlayer only takes external subtitles with the video, so the video reopens where it was, with the file added. */
    override fun addSubtitle(uri: String) {
        val current = source ?: return
        if (uri in subtitles) {
            subtitles.remove(uri)
        }
        subtitles += uri
        pickedSubtitle = true
        val position = player.currentPosition
        val playing = player.playWhenReady
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        player.setMediaItem(mediaItem(current), position)
        player.prepare()
        player.playWhenReady = playing
    }

    override fun setSubtitleTrack(id: Int) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (id < 0) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val group = groups(C.TRACK_TYPE_TEXT).getOrNull(id) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        player.trackSelectionParameters = builder.build()
        if (id < 0) surface?.subtitles?.setCues(emptyList())
    }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
        source = null
        currentUrl = null
        surface?.subtitles?.setCues(emptyList())
        _state.update { PlaybackState(volume = it.volume, muted = it.muted, rate = it.rate, buffering = false) }
    }

    override fun release() {
        scope.cancel()
        player.release()
    }

    /** The current picture from the texture view, scaled down. Main thread only. */
    override fun captureFrame(): ImageBitmap? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        val view = surface?.texture ?: return null
        if (view.width <= 0 || view.height <= 0 || !view.isAvailable) return null
        val width = minOf(view.width, 640)
        val height = (width.toLong() * view.height / view.width).toInt().coerceAtLeast(1)
        return runCatching { view.getBitmap(width, height)?.asImageBitmap() }.getOrNull()
    }

    /** Seek bar thumbnails aren't available on Android yet. */
    override fun createPreview(): SeekPreview? = null

    @Composable
    override fun Video(modifier: Modifier, fit: VideoFit, zoom: Float, pan: Offset) {
        AndroidView(
            factory = { ctx ->
                PlayerSurface(ctx).also { view ->
                    surface = view
                    view.keepScreenOn = player.playWhenReady
                    player.setVideoTextureView(view.texture)
                    view.setVideoSize(player.videoSize)
                }
            },
            update = { view -> view.apply(fit, zoom, pan) },
            modifier = modifier,
            onRelease = { view ->
                player.clearVideoTextureView(view.texture)
                if (surface === view) surface = null
            },
        )
    }

    /** The video, letterboxed to its aspect ratio, with subtitles on top. */
    private class PlayerSurface(context: Context) : FrameLayout(context) {
        val texture = TextureView(context)
        private val frame = AspectRatioFrameLayout(context).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val subtitles = SubtitleView(context).apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
        }

        init {
            addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(subtitles, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        private var videoRatio = 0f
        private var fit = VideoFit.Fit

        init {
            // The picture is cut to the screen when zoomed or cropped
            clipChildren = true
        }

        fun setVideoSize(size: VideoSize) {
            if (size.width > 0 && size.height > 0) videoRatio = size.width * size.pixelWidthHeightRatio / size.height
            applyRatio()
        }

        /** Fit modes map onto Media3's resize modes; zoom scales only the picture, so subtitles stay readable. */
        fun apply(fit: VideoFit, zoom: Float, pan: Offset) {
            this.fit = fit
            frame.resizeMode = when (fit) {
                VideoFit.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                VideoFit.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            applyRatio()
            frame.scaleX = zoom
            frame.scaleY = zoom
            frame.translationX = pan.x
            frame.translationY = pan.y
        }

        private fun applyRatio() {
            val ratio = fit.ratio ?: videoRatio
            if (ratio > 0f) frame.setAspectRatio(ratio)
        }
    }

    private fun describe(format: Format, index: Int): String {
        val language = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
        val channels = format.channelCount.takeIf { it > 0 }?.let { if (it >= 6) "5.1" else if (it == 2) "Stereo" else if (it == 1) "Mono" else "$it ch" }
        val parts = listOfNotNull(format.label?.takeIf { it.isNotBlank() }, language?.takeIf { format.label?.contains(it, ignoreCase = true) != true }, channels)
        return parts.joinToString(" · ").ifBlank { "Track ${index + 1}" }
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The video host refused the request (${error.cause?.message?.substringAfter("Response code: ")?.take(3) ?: "HTTP error"})."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Couldn't connect to the video host."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "The video file is damaged or isn't a video."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This device can't decode this video format."
        else -> "Playback failed: ${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}"
    }

    private fun subtitleMime(uri: String): String = when (uri.substringAfterLast('.').lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    companion object {
        /** Video buffered before playback starts, the Android app's default. */
        const val START_BUFFER_MS = 2_500L
    }
}
