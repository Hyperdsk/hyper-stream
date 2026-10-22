package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.DataHealth
import com.lagradost.cloudstream4.desktop.data.jsonFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonFileTest {
    @Test
    fun anUnreadableFileIsKeptAndReported() {
        val name = "jsonfile-test-${System.nanoTime()}.json"
        val file = File(AppDirs.data, name)
        file.writeText("{ this is not json")
        val store = jsonFile<Map<String, Int>>(name) { emptyMap() }
        assertEquals(emptyMap(), store.read())
        assertTrue(name in DataHealth.unreadable, "reported")
        assertTrue(AppDirs.data.listFiles()!!.any { it.name.startsWith("$name.unreadable-") && it.readText() == "{ this is not json" }, "a copy is kept")
        DataHealth.unreadable.remove(name)

        store.write(mapOf("a" to 1))
        assertEquals(mapOf("a" to 1), store.read())
        AppDirs.data.listFiles()!!.filter { it.name.startsWith(name) }.forEach { it.delete() }
    }
}
