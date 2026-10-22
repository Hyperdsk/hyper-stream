package com.lagradost.cloudstream4.desktop.plugins

import android.content.Context
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream4.desktop.AndroidHost
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Loads .cs3 extensions on Android the way the CloudStream Android app does: the file's dex code
 * runs as it is, in its own DexClassLoader whose parent is the app, which provides the library
 * and the app classes extensions link against (Plugin, CommonActivity, RepositoryManager).
 */
actual object PluginLoader {
    private val loaded = ConcurrentHashMap<String, LoadedPlugin>()

    /** Extensions often cast this to AppCompatActivity, so it is the app's activity when there is one. */
    private val loadContext: Context get() = AndroidHost.activity ?: AndroidHost.context

    actual fun isLoaded(file: File) = loaded.containsKey(file.absolutePath)
    actual fun get(file: File): LoadedPlugin? = loaded[file.absolutePath]

    @Synchronized
    actual fun load(file: File): LoadedPlugin {
        loaded[file.absolutePath]?.let { return it }
        val manifest = ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: error("${file.name} has no manifest.json")
            zip.getInputStream(entry).use { jsonMapper.readValue(it, BasePlugin.Manifest::class.java) }
        }
        val className = manifest.pluginClassName ?: error("${file.name} does not name its plugin class")

        // Android 14 refuses to load code from a file the app can still write to
        if (!file.setReadOnly() && file.canWrite()) {
            throw PluginLoadException("Couldn't make ${file.name} read-only, which Android requires before loading it.", IllegalStateException())
        }
        val classLoader = DexClassLoader(file.absolutePath, optimizedDir().absolutePath, null, PluginLoader::class.java.classLoader)
        try {
            val instance = classLoader.loadClass(className).getDeclaredConstructor().newInstance() as BasePlugin
            instance.filename = file.absolutePath
            if (instance is Plugin) instance.load(loadContext) else instance.load()
            return LoadedPlugin(file, manifest, instance, classLoader).also { loaded[file.absolutePath] = it }
        } catch (t: Throwable) {
            removePluginRegistrations(file.absolutePath)
            throw PluginLoadException(describe(t), t)
        }
    }

    @Synchronized
    actual fun unload(file: File) {
        val plugin = loaded.remove(file.absolutePath) ?: return
        runCatching { plugin.instance.beforeUnload() }
        removePluginRegistrations(file.absolutePath)
    }

    actual fun clearCache(file: File) {
        // Updating replaces the read-only file, so make it writable again
        file.setWritable(true)
        optimizedDir().listFiles()?.filter { it.name.startsWith(file.nameWithoutExtension) }?.forEach { it.delete() }
        // Since Android 8 the compiled code goes in an oat folder beside the file, not optimizedDir
        File(file.parentFile, "oat").walkBottomUp().filter { it.isFile && it.name.startsWith(file.nameWithoutExtension + ".") }.forEach { it.delete() }
    }

    private fun optimizedDir() = File(AndroidHost.context.codeCacheDir, "extensions").apply { mkdirs() }

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return when (root) {
            is NoClassDefFoundError, is ClassNotFoundException -> {
                val missing = root.message?.replace('/', '.') ?: "unknown"
                if (missing.startsWith("com.lagradost.cloudstream3")) "Uses a part of the CloudStream app this version doesn't have: $missing"
                else "Missing class: $missing"
            }
            is NoSuchMethodError -> "Uses an app function this version doesn't have: ${root.message}"
            else -> root.message ?: root.javaClass.simpleName
        }
    }
}
