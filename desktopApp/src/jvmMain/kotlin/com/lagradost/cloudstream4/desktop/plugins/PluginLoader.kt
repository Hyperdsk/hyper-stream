package com.lagradost.cloudstream4.desktop.plugins

import android.content.Context
import com.googlecode.d2j.dex.BaseDexExceptionHandler
import com.googlecode.d2j.dex.Dex2jar
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.removePluginMapping
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** A plugin that is loaded into this process, along with the providers it registered. */
class LoadedPlugin(
    val file: File,
    val manifest: BasePlugin.Manifest,
    val instance: BasePlugin,
    private val classLoader: URLClassLoader,
) {
    val providerNames: List<String>
        get() = APIHolder.allProviders.filter { it.sourcePlugin == instance.filename }.map { it.name }

    internal fun close() = runCatching { classLoader.close() }
}

/**
 * Loads published CloudStream extensions (.cs3 files) on the desktop JVM.
 *
 * A .cs3 file is a zip with an Android `classes.dex` and a `manifest.json`. The dex is
 * translated to a regular JVM jar with dex2jar, cached next to the original file, and loaded
 * with its own class loader whose parent is the app's class loader. The parent provides the
 * cross-platform `library` module plus the small Android stand-ins in this module.
 */
object PluginLoader {
    /** Bump when translation output changes, so cached jars are rebuilt. */
    private const val TRANSLATION_VERSION = 2

    private val loaded = ConcurrentHashMap<String, LoadedPlugin>()

    /** The context handed to `Plugin.load(context)`. Carries no Android behaviour. */
    private val desktopContext = Context()

    fun isLoaded(file: File) = loaded.containsKey(file.absolutePath)
    fun get(file: File): LoadedPlugin? = loaded[file.absolutePath]

    @Synchronized
    fun load(file: File): LoadedPlugin {
        loaded[file.absolutePath]?.let { return it }

        val (manifest, jars) = ZipFile(file).use { zip ->
            val manifestEntry = zip.getEntry("manifest.json")
                ?: error("${file.name} has no manifest.json")
            val manifest = zip.getInputStream(manifestEntry).use {
                jsonMapper.readValue(it, BasePlugin.Manifest::class.java)
            }
            val dexEntries = zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .toList()
            check(dexEntries.isNotEmpty()) { "${file.name} contains no code" }

            val jars = dexEntries.map { entry ->
                val jar = convertedJar(file, entry.name)
                if (!jar.exists()) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    translate(bytes, jar)
                }
                jar
            }
            manifest to jars
        }

        val className = manifest.pluginClassName
            ?: error("${file.name} does not name its plugin class")

        val classLoader = URLClassLoader(
            "cs3:${file.nameWithoutExtension}",
            jars.map { it.toURI().toURL() }.toTypedArray(),
            PluginLoader::class.java.classLoader,
        )

        try {
            val instance = classLoader.loadClass(className)
                .getDeclaredConstructor()
                .newInstance() as BasePlugin
            instance.filename = file.absolutePath

            if (instance is Plugin) instance.load(desktopContext) else instance.load()

            val plugin = LoadedPlugin(file, manifest, instance, classLoader)
            loaded[file.absolutePath] = plugin
            return plugin
        } catch (t: Throwable) {
            // Remove anything it registered before failing, then report a readable reason
            removeRegistrations(file.absolutePath)
            runCatching { classLoader.close() }
            throw PluginLoadException(describe(t), t)
        }
    }

    @Synchronized
    fun unload(file: File) {
        val plugin = loaded.remove(file.absolutePath) ?: return
        runCatching { plugin.instance.beforeUnload() }
        removeRegistrations(file.absolutePath)
        plugin.close()
    }

    /** Deletes cached jars for a plugin file, so the next load translates it again. */
    fun clearCache(file: File) {
        AppDirs.convertedExtensions.listFiles()
            ?.filter { it.name.startsWith(cachePrefix(file)) }
            ?.forEach { it.delete() }
    }

    private fun removeRegistrations(filename: String) {
        APIHolder.apis.filter { it.sourcePlugin == filename }.forEach { removePluginMapping(it) }
        APIHolder.allProviders.withLock {
            APIHolder.allProviders.removeAll { it.sourcePlugin == filename }
        }
        extractorApis.withLock {
            extractorApis.removeAll { it.sourcePlugin == filename }
        }
    }

    private fun cachePrefix(file: File) =
        "${file.parentFile.name}_${file.nameWithoutExtension}_"

    /** Cache key includes size and modification time, so an updated .cs3 gets translated again. */
    private fun convertedJar(file: File, dexName: String): File {
        val prefix = cachePrefix(file)
        val name = "$prefix${file.length()}_${file.lastModified()}_v${TRANSLATION_VERSION}_${dexName.removeSuffix(".dex")}.jar"
        val jar = File(AppDirs.convertedExtensions, name)
        if (!jar.exists()) {
            // Remove stale translations of older versions
            AppDirs.convertedExtensions.listFiles()
                ?.filter { it.name.startsWith(prefix) && it.name.endsWith("_${dexName.removeSuffix(".dex")}.jar") }
                ?.forEach { it.delete() }
        }
        return jar
    }

    private fun translate(dex: ByteArray, jar: File) {
        val tmp = File(jar.parentFile, "${jar.name}.tmp")
        tmp.delete()
        Dex2jar.from(dex)
            .reUseReg(false)
            .topoLogicalSort()
            .skipDebug(false)
            .optimizeSynchronized(true)
            .noCode(false)
            // A method that fails to translate is replaced with one that throws, instead of failing the whole extension
            .withExceptionHandler(BaseDexExceptionHandler())
            .to(tmp.toPath())
        val fixed = File(jar.parentFile, "${jar.name}.fixed")
        try {
            InnerClassFixer.fix(tmp, fixed)
            check(fixed.renameTo(jar)) { "Could not save translated extension ${jar.name}" }
        } finally {
            tmp.delete()
            fixed.delete()
        }
    }

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return when (root) {
            is NoClassDefFoundError, is ClassNotFoundException -> {
                val missing = root.message?.replace('/', '.') ?: "unknown"
                if (missing.startsWith("android") || missing.startsWith("com.lagradost.cloudstream3")) {
                    "Uses Android-only code that is not available on desktop: $missing"
                } else {
                    "Missing class: $missing"
                }
            }

            is NoSuchMethodError -> "Uses an app function that is not available on desktop: ${root.message}"
            else -> root.message ?: root.javaClass.simpleName
        }
    }
}

class PluginLoadException(message: String, cause: Throwable) : Exception(message, cause)
