package com.lagradost.cloudstream4.desktop.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.removePluginMapping
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.plugins.BasePlugin
import java.io.File

/** A plugin that is loaded into this process, along with the providers it registered. */
class LoadedPlugin(
    val file: File,
    val manifest: BasePlugin.Manifest,
    val instance: BasePlugin,
    private val classLoader: ClassLoader,
    /** Android classes replaced by placeholders, usually from settings screens. */
    val stubbed: List<String> = emptyList(),
) {
    val providerNames: List<String>
        get() = APIHolder.allProviders.filter { it.sourcePlugin == instance.filename }.map { it.name }

    internal fun close() = runCatching { (classLoader as? java.io.Closeable)?.close() }
}

/**
 * Loads published CloudStream extensions (.cs3 files) into this process. Desktop translates their
 * Android code to JVM bytecode first; Android loads it as it is.
 */
expect object PluginLoader {
    fun isLoaded(file: File): Boolean
    fun get(file: File): LoadedPlugin?
    fun load(file: File): LoadedPlugin
    fun unload(file: File)

    /** Deletes anything cached for a plugin file, so the next load starts fresh. */
    fun clearCache(file: File)
}

class PluginLoadException(message: String, cause: Throwable) : Exception(message, cause)

/** Removes the providers and extractors a plugin registered. */
internal fun removePluginRegistrations(filename: String) {
    APIHolder.apis.filter { it.sourcePlugin == filename }.forEach { removePluginMapping(it) }
    APIHolder.allProviders.withLock {
        APIHolder.allProviders.removeAll { it.sourcePlugin == filename }
    }
    extractorApis.withLock {
        extractorApis.removeAll { it.sourcePlugin == filename }
    }
}
