package com.lagradost.cloudstream4.desktop.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.StateFlow

data class Track(val id: Int, val name: String)

data class PlaybackState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    /** The player's buffer fill while buffering, 0 to 100. */
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

/** What the player shows while streaming. */
data class StreamStats(
    /** Download speed from the hosts, smoothed. */
    val bytesPerSecond: Long = 0,
    /** Upstream connections open right now. */
    val connections: Int = 0,
    /** Video already fetched and waiting in memory ahead of the player. */
    val aheadBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Size of the video file being streamed, when known (not for HLS). */
    val streamSize: Long = 0,
)

/**
 * How the picture fills the player. [ratio] forces an aspect ratio (width / height) for videos
 * that report the wrong one; the others keep the video's own.
 */
enum class VideoFit(val label: String, val ratio: Float? = null) {
    Fit("Fit"),
    Crop("Crop to fill"),
    Stretch("Stretch"),
    Ratio16x9("16:9", 16f / 9f),
    Ratio4x3("4:3", 4f / 3f),
    Ratio21x9("21:9", 21f / 9f);

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: Fit
    }
}

/**
 * A url the player can open, with the headers it must send. [mimeType] tells players that can't
 * guess the format from the url what it is (HLS playlists often have no .m3u8 in their name).
 */
data class StreamSource(val url: String, val headers: Map<String, String> = emptyMap(), val mimeType: String? = null) {
    companion object {
        const val HLS = "application/x-mpegURL"
        const val DASH = "application/dash+xml"
    }
}

/**
 * The video player. VLC on desktop, ExoPlayer (Media3) on Android. The player screen and model
 * only talk to this, so both apps share them.
 */
interface VideoEngine {
    val state: StateFlow<PlaybackState>

    /** The url currently opened, so seek bar previews can open the same video. */
    val currentUrl: String?

    /** How much video the player buffers before it starts, in milliseconds. */
    val startBufferMs: Long

    /** @param subtitles uris of external subtitle files, added but not selected */
    fun open(source: StreamSource, startMs: Long = 0, subtitles: List<String> = emptyList())
    fun togglePause()
    fun seekTo(ms: Long)
    fun seekBy(ms: Long) = seekTo(state.value.timeMs + ms)
    fun setVolume(volume: Int)
    fun toggleMute()
    fun setRate(rate: Float)
    fun setAudioTrack(id: Int)

    /** Adds a subtitle file (a local file uri) and shows it. */
    fun addSubtitle(uri: String)
    fun setSubtitleTrack(id: Int)
    fun stop()
    fun release()

    /** The picture on screen right now, for Continue watching cards. Null when there is none. */
    fun captureFrame(): ImageBitmap?

    /** A silent second player for seek bar thumbnails, or null where that isn't supported. Release it when done. */
    fun createPreview(): SeekPreview?

    /**
     * Draws the video. [fit] decides how it fills the space; [zoom] (1 = none) enlarges it
     * around the centre, moved by [pan] in pixels. Subtitles stay in place where the player draws them itself.
     */
    @Composable
    fun Video(modifier: Modifier, fit: VideoFit, zoom: Float, pan: Offset)
}

/** Seek bar thumbnails from a second player on the same video. */
interface SeekPreview {
    /** The frame for the most recently requested time, or null while it loads. */
    val thumbnail: ImageBitmap?
    fun open(url: String?)
    fun request(timeMs: Long)
    fun release()

    companion object {
        const val BUCKET_MS = 5_000L
        const val WIDTH = 320
    }
}

/**
 * Prepares links for the player. On desktop links go through a local proxy that adds headers
 * and streams over several connections; Android's player sends the headers itself.
 */
expect object StreamGateway {
    val stats: StateFlow<StreamStats>

    /** [parallel]: [url] is a single video file, stream it over several connections where supported. */
    fun prepare(url: String, referer: String?, headers: Map<String, String>, parallel: Boolean = false): StreamSource

    /** Forgets every prepared link, when the player closes or switches source. */
    fun clear()

    /** The url a seek bar preview should open for the playing url, at low priority where supported. */
    fun previewUrl(url: String): String
}
