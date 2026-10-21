package com.lagradost.cloudstream4.desktop

import android.annotation.SuppressLint
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.lagradost.cloudstream4.desktop.data.HistoryEntry
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.data.SnapshotProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Puts the app's Continue watching on the TV home screen, kept in step with the app: titles are
 * added and updated as you watch, and removed when they leave the app's row. Each card opens the
 * player where you stopped. Two places, since launchers differ:
 * - a "Hyper Stream" channel (a row of its own), which Google TV shows for any app;
 * - Android's Watch Next list, the system "Continue watching" row, which Google TV only fills
 *   from apps Google has verified.
 */
object WatchNext {
    const val SCHEME = "hyperstream"
    private const val HOST = "resume"

    /** The link a card opens, and how the app reads it back. */
    fun linkFor(apiName: String, parentUrl: String): Uri =
        Uri.Builder().scheme(SCHEME).authority(HOST).appendQueryParameter("api", apiName).appendQueryParameter("url", parentUrl).build()

    fun parse(uri: Uri?): Pair<String, String>? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val api = uri.getQueryParameter("api") ?: return null
        val url = uri.getQueryParameter("url") ?: return null
        return api to url
    }

    @OptIn(FlowPreview::class)
    fun start() {
        if (!AndroidHost.isTv) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            LibraryStore.state.map { it.continueWatching }
                .distinctUntilChanged()
                // Progress saves often while watching; the home screen only needs the latest
                .debounce(3_000)
                .collect { entries ->
                    runCatching { syncChannel(entries) }.onFailure { it.printStackTrace() }
                    runCatching { sync(entries) }.onFailure { it.printStackTrace() }
                }
        }
    }

    private fun idOf(entry: HistoryEntry) = "${entry.apiName}|${entry.parentUrl}"

    @SuppressLint("RestrictedApi")
    private fun sync(entries: List<HistoryEntry>) {
        val context = AndroidHost.context
        val resolver = context.contentResolver
        // Only this app's programs come back from the query
        val existing = HashMap<String, Long>()
        resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val program = WatchNextProgram.fromCursor(cursor)
                program.internalProviderId?.let { existing[it] = program.id }
            }
        }
        val helper = PreviewChannelHelper(context)
        val kept = entries.take(20).mapNotNull { entry ->
            runCatching {
                val program = build(entry, context.packageName)
                val id = existing[idOf(entry)]
                if (id != null) {
                    helper.updateWatchNextProgram(program, id)
                    id
                } else {
                    helper.publishWatchNextProgram(program)
                }
            }.getOrNull()
        }.toSet()
        // Gone from the app's Continue watching: gone from the home screen too
        (existing.values.toSet() - kept).forEach { id ->
            runCatching { resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) }
        }
        android.util.Log.i("WatchNext", "Continue watching on the home screen: ${kept.size} of ${entries.size} titles")
    }

    @SuppressLint("RestrictedApi")
    private fun build(entry: HistoryEntry, packageName: String): WatchNextProgram {
        val episodic = entry.season != null || entry.episode != null
        val snapshot = entry.snapshotPath?.let(::File)?.takeIf { it.isFile }
        val builder = WatchNextProgram.Builder()
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setType(if (episodic) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
            .setTitle(entry.name)
            .setIntentUri(linkFor(entry.apiName, entry.parentUrl))
            .setInternalProviderId(idOf(entry))
            .setLastEngagementTimeUtcMillis(entry.updatedAt)
            .setDurationMillis(entry.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setLastPlaybackPositionMillis(entry.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (episodic) {
            entry.episodeName?.let { builder.setEpisodeTitle(it) }
            entry.season?.let { builder.setSeasonNumber(it) }
            entry.episode?.let { builder.setEpisodeNumber(it) }
        }
        // Where you stopped, 16:9, or the poster when there is no picture yet
        if (snapshot != null) {
            builder.setPosterArtUri(SnapshotProvider.uriFor(packageName, snapshot, entry.updatedAt))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        } else if (entry.posterUrl != null) {
            builder.setPosterArtUri(Uri.parse(entry.posterUrl))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
        }
        return builder.build()
    }

    private const val CHANNEL_ID = "continue-watching"

    /** The app's channel on the home screen, created once and found again by its id. */
    @SuppressLint("RestrictedApi")
    private fun channel(): Long {
        val context = AndroidHost.context
        val helper = PreviewChannelHelper(context)
        helper.allChannels.firstOrNull { it.internalProviderId == CHANNEL_ID }?.let { return it.id }
        val logo = runCatching {
            val icon = context.packageManager.getApplicationIcon(context.packageName)
            android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = android.graphics.Canvas(bitmap)
                icon.setBounds(0, 0, 256, 256)
                icon.draw(canvas)
            }
        }.getOrNull()
        val channel = androidx.tvprovider.media.tv.PreviewChannel.Builder()
            .setDisplayName("Hyper Stream")
            .setDescription("Continue watching")
            .setAppLinkIntentUri(Uri.parse("$SCHEME://open"))
            .setInternalProviderId(CHANNEL_ID)
            .apply { if (logo != null) setLogo(logo) }
            .build()
        val id = helper.publishDefaultChannel(channel)
        // Asks the launcher to show the row; Google TV shows an app's first channel by itself
        runCatching { TvContractCompat.requestChannelBrowsable(context, id) }
        return id
    }

    @SuppressLint("RestrictedApi")
    private fun syncChannel(entries: List<HistoryEntry>) {
        val context = AndroidHost.context
        val resolver = context.contentResolver
        val channelId = channel()
        val existing = HashMap<String, Long>()
        resolver.query(TvContractCompat.PreviewPrograms.CONTENT_URI, androidx.tvprovider.media.tv.PreviewProgram.PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val program = androidx.tvprovider.media.tv.PreviewProgram.fromCursor(cursor)
                if (program.channelId != channelId) continue
                program.internalProviderId?.let { existing[it] = program.id }
            }
        }
        val helper = PreviewChannelHelper(context)
        val shown = entries.take(20)
        val kept = shown.mapIndexedNotNull { rank, entry ->
            runCatching {
                val program = buildPreview(entry, channelId, context.packageName, weight = shown.size - rank)
                val id = existing[idOf(entry)]
                if (id != null) {
                    helper.updatePreviewProgram(id, program)
                    id
                } else {
                    helper.publishPreviewProgram(program)
                }
            }.getOrNull()
        }.toSet()
        (existing.values.toSet() - kept).forEach { id ->
            runCatching { resolver.delete(TvContractCompat.buildPreviewProgramUri(id), null, null) }
        }
        android.util.Log.i("WatchNext", "Hyper Stream channel: ${kept.size} titles")
    }

    @SuppressLint("RestrictedApi")
    private fun buildPreview(entry: HistoryEntry, channelId: Long, packageName: String, weight: Int): androidx.tvprovider.media.tv.PreviewProgram {
        val episodic = entry.season != null || entry.episode != null
        val snapshot = entry.snapshotPath?.let(::File)?.takeIf { it.isFile }
        val minutesLeft = ((entry.durationMs - entry.positionMs) / 60_000).toInt()
        val description = listOfNotNull(
            when {
                entry.season != null && entry.episode != null -> "S${entry.season}:E${entry.episode}"
                entry.episode != null -> "Episode ${entry.episode}"
                else -> null
            },
            entry.episodeName,
            when {
                entry.durationMs <= 0 -> null
                minutesLeft >= 60 -> "${minutesLeft / 60}h ${minutesLeft % 60}m left"
                minutesLeft >= 1 -> "$minutesLeft min left"
                else -> "Almost done"
            },
        ).joinToString(" · ")
        val builder = androidx.tvprovider.media.tv.PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(if (episodic) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
            .setTitle(entry.name)
            .setDescription(description)
            .setIntentUri(linkFor(entry.apiName, entry.parentUrl))
            .setInternalProviderId(idOf(entry))
            // Most recently watched first
            .setWeight(weight)
            .setDurationMillis(entry.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setLastPlaybackPositionMillis(entry.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (snapshot != null) {
            builder.setPosterArtUri(SnapshotProvider.uriFor(packageName, snapshot, entry.updatedAt))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        } else if (entry.posterUrl != null) {
            builder.setPosterArtUri(Uri.parse(entry.posterUrl))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
        }
        return builder.build()
    }
}
