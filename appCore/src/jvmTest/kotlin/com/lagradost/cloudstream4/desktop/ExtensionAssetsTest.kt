package com.lagradost.cloudstream4.desktop

import android.content.res.AssetManager
import android.content.res.Resources
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** An extension's assets come from its .cs3, like Eclipsia's NetMirror reads soryn/source.json. */
class ExtensionAssetsTest {
    @Test
    fun assetsAreReadFromThePackage() {
        val cs3 = File(Files.createTempDirectory("cs-assets").toFile(), "Ext.cs3")
        ZipOutputStream(cs3.outputStream()).use { zip ->
            listOf("manifest.json" to "{}", "assets/soryn/source.json" to """{"variants":[]}""", "assets/soryn/catalog.js" to "x()", "assets/top.txt" to "t")
                .forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        val assets = Resources(AssetManager(cs3)).getAssets()
        assertEquals("""{"variants":[]}""", assets.open("soryn/source.json").use { String(it.readBytes()) })
        assertContentEquals(arrayOf("source.json", "catalog.js").sortedArray(), assets.list("soryn").sortedArray())
        assertContentEquals(arrayOf("soryn", "top.txt").sortedArray(), assets.list("").sortedArray())
        assertFailsWith<FileNotFoundException> { assets.open("missing.json") }
        // No package (a plugin loaded some other way): nothing, not a crash
        assertFailsWith<FileNotFoundException> { Resources().getAssets().open("soryn/source.json") }
        cs3.parentFile.deleteRecursively()
    }
}
