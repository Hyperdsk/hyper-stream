package android.content.res

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Desktop stand-in for android.content.res.AssetManager: an extension's assets are the
 * "assets/" entries of its .cs3 file. Extensions like Eclipsia's NetMirror keep their list of
 * providers and their scripts there; with a do-nothing placeholder they registered nothing.
 */
open class AssetManager(private val packageFile: File?) {
    @Throws(java.io.IOException::class)
    open fun open(fileName: String): InputStream {
        val zipFile = packageFile ?: throw FileNotFoundException(fileName)
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("assets/" + fileName.trimStart('/')) ?: throw FileNotFoundException(fileName)
            return ByteArrayInputStream(zip.getInputStream(entry).use { it.readBytes() })
        }
    }

    @Throws(java.io.IOException::class)
    open fun open(fileName: String, accessMode: Int): InputStream = open(fileName)

    /** The names directly inside [path], like Android: files and folders, no trailing slashes. */
    open fun list(path: String): Array<String> {
        val zipFile = packageFile ?: return emptyArray()
        val prefix = "assets/" + path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().map { it.name }.filter { it.startsWith(prefix) && it.length > prefix.length }
                .map { it.removePrefix(prefix).substringBefore('/') }.distinct().toList().toTypedArray()
        }
    }

    open fun close() {}

    companion object {
        const val ACCESS_UNKNOWN = 0
        const val ACCESS_RANDOM = 1
        const val ACCESS_STREAMING = 2
        const val ACCESS_BUFFER = 3
    }
}
