package com.lagradost.cloudstream4.desktop.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.jsonFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives each provider a name of its own. The app finds providers by name (title pages, history,
 * the player), so two extensions with a provider of the same name were mixed up: AllOtt's and
 * CNC Verse's are both "Prime Video", and AllOtt's titles opened with CNC Verse's code, which
 * failed. Now the later one becomes "Prime Video (AllOtt)".
 *
 * Which extension keeps the plain name is saved the first time two meet, so it stays the same
 * when an update loads them in another order (history and bookmarks are saved by name).
 */
object ProviderNames {
    private val file = jsonFile<Map<String, String>>("provider-names.json") { emptyMap() }

    /** A shared name, to the extension (its path in the Extensions folder) that keeps it plain. */
    private val owners = ConcurrentHashMap(file.read())

    private fun key(sourcePlugin: String?, extensions: File = AppDirs.extensions): String {
        val path = File(sourcePlugin ?: return "").absolutePath
        return path.removePrefix(extensions.absolutePath + File.separator)
    }

    /** Call after loading the extension [sourcePlugin] (its file path, as MainAPI.sourcePlugin). */
    @Synchronized
    fun makeUnique(sourcePlugin: String) {
        val all = APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
        val me = key(sourcePlugin)
        var saved = false
        for (api in all.filter { it.sourcePlugin == sourcePlugin }) {
            val name = api.name
            val others = all.filter { it !== api && it.sourcePlugin != null && it.sourcePlugin != sourcePlugin && it.name == name }
            if (others.isEmpty()) continue
            // The first to have it keeps it, unless this one was recorded as its owner earlier
            val owner = owners[name] ?: key(others.first().sourcePlugin).also {
                owners[name] = it
                saved = true
            }
            if (owner == me) others.forEach { rename(it, all) } else rename(api, all)
        }
        if (saved) file.write(HashMap(owners))
    }

    private fun rename(api: MainAPI, all: List<MainAPI>) {
        val name = api.name
        val taken = all.map { it.name.lowercase() }.toSet()
        val plugin = File(api.sourcePlugin ?: "").nameWithoutExtension.removeSuffix("Provider").trim()
        // The repository's GitHub user, for two extensions with the same file name too
        val owner = Regex("githubusercontent\\.com_([^_]+)_").find(File(api.sourcePlugin ?: "").parentFile?.name.orEmpty())?.groupValues?.get(1)
        val candidates = listOfNotNull(
            plugin.takeIf { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }?.let { "$name ($it)" },
            owner?.let { "$name ($it)" },
        ) + (2..99).map { "$name ($it)" }
        val unique = candidates.first { it.lowercase() !in taken }
        api.name = unique
        // An extension whose name can't be changed (a fixed getter) keeps its own
        if (api.name != unique) return
        // The name lookup map is built from names
        APIHolder.removePluginMapping(api)
        APIHolder.addPluginMapping(api)
        println("Renamed provider $name from ${File(api.sourcePlugin ?: "").name} to $unique: another extension has a provider called $name")
    }
}
