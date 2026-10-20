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

/**
 * Loads published CloudStream extensions (.cs3 files) on the desktop JVM.
 *
 * A .cs3 file is a zip with an Android `classes.dex` and a `manifest.json`. The dex is
 * translated to a regular JVM jar with dex2jar, cached next to the original file, and loaded
 * with its own class loader whose parent is the app's class loader. The parent provides the
 * cross-platform `library` module plus the small Android stand-ins in this module.
 */
actual object PluginLoader {
    /** Bump when translation output changes, so cached jars are rebuilt. */
    private const val TRANSLATION_VERSION = 7

    private val loaded = ConcurrentHashMap<String, LoadedPlugin>()

    /**
     * The context handed to `Plugin.load(context)`. Many extensions cast it straight to
     * AppCompatActivity (the Android app passes its main activity), so it is one. It only offers
     * what desktop supports, mainly getSharedPreferences. Extension settings screens that need
     * more are never opened on desktop.
     */
    private val desktopContext: Context get() = DesktopActivity.instance

    actual fun isLoaded(file: File) = loaded.containsKey(file.absolutePath)
    actual fun get(file: File): LoadedPlugin? = loaded[file.absolutePath]

    @Synchronized
    actual fun load(file: File): LoadedPlugin {
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

        val parent = PluginLoader::class.java.classLoader
        // Placeholders for Android classes the extension mentions but desktop doesn't have
        val stubJar = File(jars.first().path.removeSuffix(".jar") + "_android-stubs.jar")
        val stubListFile = File(stubJar.path + ".txt")
        val stubbed = if (stubListFile.exists() && stubListFile.lastModified() >= jars.maxOf { it.lastModified() }) {
            stubListFile.readLines().filter { it.isNotBlank() }
        } else {
            val result = AndroidStubs.generate(jars, parent, stubJar)
            if (result == null) stubJar.delete()
            stubListFile.writeText(result?.stubbed.orEmpty().joinToString("\n"))
            result?.stubbed.orEmpty()
        }
        val urls = jars + listOfNotNull(stubJar.takeIf { stubbed.isNotEmpty() && it.exists() })

        val classLoader = URLClassLoader(
            "cs3:${file.nameWithoutExtension}",
            urls.map { it.toURI().toURL() }.toTypedArray(),
            parent,
        )

        try {
            val instance = classLoader.loadClass(className)
                .getDeclaredConstructor()
                .newInstance() as BasePlugin
            instance.filename = file.absolutePath

            if (instance is Plugin) instance.load(desktopContext) else instance.load()

            val plugin = LoadedPlugin(file, manifest, instance, classLoader, stubbed)
            loaded[file.absolutePath] = plugin
            return plugin
        } catch (t: Throwable) {
            // Remove anything it registered before failing, then report a readable reason
            removePluginRegistrations(file.absolutePath)
            runCatching { classLoader.close() }
            throw PluginLoadException(describe(t), t)
        }
    }

    @Synchronized
    actual fun unload(file: File) {
        val plugin = loaded.remove(file.absolutePath) ?: return
        runCatching { plugin.instance.beforeUnload() }
        removePluginRegistrations(file.absolutePath)
        plugin.close()
    }

    /** Deletes cached jars for a plugin file, so the next load translates it again. */
    actual fun clearCache(file: File) {
        AppDirs.convertedExtensions.listFiles()
            ?.filter { it.name.startsWith(cachePrefix(file)) }
            ?.forEach { it.delete() }
    }

    private fun cachePrefix(file: File) =
        "${file.parentFile.name}_${file.nameWithoutExtension}_"

    /** Cache key includes size and modification time, so an updated .cs3 gets translated again. */
    private fun convertedJar(file: File, dexName: String): File {
        val prefix = cachePrefix(file)
        val key = "$prefix${file.length()}_${file.lastModified()}_v${TRANSLATION_VERSION}_"
        val jar = File(AppDirs.convertedExtensions, "$key${dexName.removeSuffix(".dex")}.jar")
        if (!jar.exists()) {
            // Remove everything from older versions of this extension or of the translation
            AppDirs.convertedExtensions.listFiles()
                ?.filter { it.name.startsWith(prefix) && !it.name.startsWith(key) }
                ?.forEach { it.delete() }
        }
        return jar
    }

    private fun translate(dex: ByteArray, jar: File) {
        // Classes go to a folder first and are zipped by InnerClassFixer. dex2jar's own jar
        // output needs the jdk.zipfs module, which a trimmed app runtime may not include.
        val tmp = File(jar.parentFile, "${jar.name}.classes")
        tmp.deleteRecursively()
        tmp.mkdirs()
        Dex2jar.from(dex)
            .reUseReg(false)
            .topoLogicalSort()
            .skipDebug(false)
            .optimizeSynchronized(true)
            .noCode(false)
            // Kotlin uses names like "constructor-impl" for inline classes, which must stay as they are
            .dontSanitizeNames(true)
            // A method that fails to translate is replaced with one that throws, instead of failing the whole extension
            .withExceptionHandler(BaseDexExceptionHandler())
            .doTranslate(tmp.toPath())
        val fixed = File(jar.parentFile, "${jar.name}.fixed")
        try {
            InnerClassFixer.fix(tmp, fixed)
            check(fixed.renameTo(jar)) { "Could not save translated extension ${jar.name}" }
        } finally {
            tmp.deleteRecursively()
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
