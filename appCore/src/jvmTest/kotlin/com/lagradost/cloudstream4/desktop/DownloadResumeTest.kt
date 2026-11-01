package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.data.HistoryEntry
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.download.DownloadEntry
import com.lagradost.cloudstream4.desktop.download.DownloadStatus
import com.lagradost.cloudstream4.desktop.ui.downloadPlayRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** A downloaded episode plays on from where it was left, as it does from the title's page. */
class DownloadResumeTest {
    private fun entry(data: String, episode: Int) = DownloadEntry(
        id = "dl-$data", apiName = "ResumeDl", parentUrl = "https://example.invalid/show", title = "Show",
        posterUrl = null, posterHeaders = null, episodeData = data, episodeName = null, season = 1, episode = episode,
        status = DownloadStatus.Completed,
    )

    @Test
    fun aDownloadResumesWhereItWasLeft() {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-dl-resume").toString())
        LibraryStore.saveProgress(
            HistoryEntry("https://example.invalid/show", "ResumeDl", "Show", null, null, "ep2", null, 1, 2, 600_000, 1_200_000)
        )
        val done = listOf(entry("ep1", 1), entry("ep2", 2))
        val request = downloadPlayRequest(done[1], done)
        assertEquals(1, request.index)
        assertEquals(600_000, request.startMs, "plays on from 10:00, where it was left")
        assertEquals(0, downloadPlayRequest(done[0], done).startMs, "one not started yet starts at the beginning")
        // Watched to the end: starts over
        LibraryStore.saveProgress(
            HistoryEntry("https://example.invalid/show", "ResumeDl", "Show", null, null, "ep2", null, 1, 2, 1_200_000, 1_200_000)
        )
        assertEquals(0, downloadPlayRequest(done[1], done).startMs)
    }
}
