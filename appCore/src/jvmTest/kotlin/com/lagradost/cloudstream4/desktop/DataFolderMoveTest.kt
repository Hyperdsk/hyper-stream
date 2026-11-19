package com.lagradost.cloudstream4.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The data folder moved from CloudStream to HyperStream: updating keeps what was in the old one. Temp folders only. */
class DataFolderMoveTest {
    @Test
    fun oldFolderMovesOverOnce() {
        val base = Files.createTempDirectory("cs-folder-move").toFile()
        File(base, "CloudStream/data").mkdirs()
        File(base, "CloudStream/data/history.json").writeText("[1]")

        val folder = Platform.dataFolderIn(base, "HyperStream", "CloudStream")
        assertEquals(File(base, "HyperStream"), folder)
        assertEquals("[1]", File(folder, "data/history.json").readText(), "history came along")
        assertFalse(File(base, "CloudStream").exists(), "the old folder is gone, not copied")

        // A new old folder later (an old version run again) is left alone: the new one wins
        File(base, "CloudStream").mkdirs()
        assertEquals(File(base, "HyperStream"), Platform.dataFolderIn(base, "HyperStream", "CloudStream"))
        assertTrue(File(base, "CloudStream").exists())

        // A fresh install: the new name, nothing to move
        val fresh = Files.createTempDirectory("cs-folder-fresh").toFile()
        assertEquals(File(fresh, "HyperStream"), Platform.dataFolderIn(fresh, "HyperStream", "CloudStream"))
        base.deleteRecursively(); fresh.deleteRecursively()
    }
}
