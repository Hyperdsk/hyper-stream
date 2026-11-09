package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.DesktopSharedPreferences
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Extensions keep logins and settings in these files: a damaged one must not just vanish. */
class DesktopPreferencesTest {
    @Test
    fun aDamagedFileIsKeptAndSavesAreWritten() {
        // Only used if no other test set up the data folder first
        if (System.getProperty("cloudstream.dataDir") == null) {
            System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-prefs").toString())
        }
        val name = "prefs-test-${System.nanoTime()}"
        val folder = File(AppDirs.data, "extension-preferences").apply { mkdirs() }
        val file = File(folder, "$name.json")
        // Cut short by a crash while saving, as writing in place could leave it
        file.writeText("{\"token\": \"abc\", \"user")

        val prefs = DesktopSharedPreferences.get(name)
        assertNull(prefs.getString("token", null))
        assertTrue(folder.listFiles().orEmpty().any { it.name.startsWith("$name.json.unreadable-") }, "a copy of the damaged file is kept")

        assertTrue(prefs.edit().putString("token", "new").commit(), "commit reports the save")
        assertEquals("new", jsonMapper.readTree(file)["token"].asText())
        assertFalse(File(folder, "$name.json.tmp").exists(), "no temp file left")
    }
}
