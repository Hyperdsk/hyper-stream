package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.data.HistoryEntry
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Continue watching picture goes once nothing uses it, instead of filling the data folder. */
class LibrarySnapshotsTest {
    @Test
    fun aRemovedEpisodesPictureIsDeleted() {
        if (System.getProperty("cloudstream.dataDir") == null) {
            System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-snapshots").toString())
        }
        val picture = File(Files.createTempDirectory("cs-snapshot-pics").toFile(), "picture.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val entry = HistoryEntry(
            "snapshot-show", "Prov", "Snapshot Show", null, null, "snapshot-ep", null, 1, 1, 60_000, 1_200_000,
            updatedAt = 1_000, snapshotPath = picture.path,
        )
        LibraryStore.saveProgress(entry)
        assertTrue(picture.exists())

        // Removed on another device
        LibraryStore.applyRemoteHistory(LibraryStore.historyKey(entry), null)
        assertFalse(picture.exists(), "its picture was deleted")
    }
}
