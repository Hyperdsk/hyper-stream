package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream4.desktop.data.AppSettings
import com.lagradost.cloudstream4.desktop.data.SettingsStore

/**
 * Which source plays first: by quality (highest, or a chosen one first), by servers the user
 * starred, and never by servers the user avoids unless nothing else is left.
 */
object SourcePreferences {
    /** The names a preference can match: the provider ("MovieBox") and the server ("Hub-Cloud"). */
    fun names(info: SourceInfo): List<String> = listOfNotNull(info.provider, info.server).map { it.trim() }.filter { it.isNotBlank() }

    /** Position in the preferred list, or null when none of the source's names is preferred. */
    fun preferredRank(info: SourceInfo, settings: AppSettings): Int? =
        settings.preferredSources.indexOfFirst { p -> names(info).any { it.equals(p, ignoreCase = true) } }.takeIf { it >= 0 }

    fun isAvoided(info: SourceInfo, settings: AppSettings): Boolean =
        settings.avoidedSources.any { a -> names(info).any { it.equals(a, ignoreCase = true) } }

    /**
     * Quality order. With a chosen quality: that one first, then the nearest lower ones, then
     * higher ones from the nearest up. With none chosen: highest first.
     */
    private fun qualityKey(quality: Int, preferred: Int?): Pair<Int, Int> = when {
        preferred == null -> 0 to -quality
        quality == preferred -> 0 to 0
        quality < preferred -> 1 to -quality
        else -> 2 to quality
    }

    /** Orders link indices best first. */
    fun order(links: List<ExtractorLink>, indices: Collection<Int>, settings: AppSettings = SettingsStore.state.value): List<Int> {
        val infos = indices.associateWith { links[it].info() }
        val byQuality = compareBy<Int>({ qualityKey(links[it].quality, settings.preferredQuality).first },
            { qualityKey(links[it].quality, settings.preferredQuality).second })
        val byServer = compareBy<Int> { preferredRank(infos.getValue(it), settings) ?: Int.MAX_VALUE }
        val avoidedLast = compareBy<Int> { isAvoided(infos.getValue(it), settings) }
        val main = if (settings.sourceOrder == "servers") byServer.then(byQuality) else byQuality.then(byServer)
        return indices.sortedWith(avoidedLast.then(main))
    }

    /** Whether a source is what the user is waiting for: a preferred server, or the wanted quality. */
    fun matches(link: ExtractorLink, settings: AppSettings = SettingsStore.state.value): Boolean {
        val info = link.info()
        if (isAvoided(info, settings)) return false
        if (preferredRank(info, settings) != null) return true
        val wanted = settings.preferredQuality ?: 2160
        return link.quality == wanted
    }

    fun togglePreferred(name: String) = SettingsStore.update { s ->
        val has = s.preferredSources.any { it.equals(name, ignoreCase = true) }
        s.copy(
            preferredSources = if (has) s.preferredSources.filterNot { it.equals(name, ignoreCase = true) } else s.preferredSources + name,
            avoidedSources = s.avoidedSources.filterNot { it.equals(name, ignoreCase = true) },
        )
    }

    fun toggleAvoided(name: String) = SettingsStore.update { s ->
        val has = s.avoidedSources.any { it.equals(name, ignoreCase = true) }
        s.copy(
            avoidedSources = if (has) s.avoidedSources.filterNot { it.equals(name, ignoreCase = true) } else s.avoidedSources + name,
            preferredSources = s.preferredSources.filterNot { it.equals(name, ignoreCase = true) },
        )
    }

    fun moveUp(name: String) = SettingsStore.update { s ->
        val i = s.preferredSources.indexOf(name)
        if (i <= 0) s else s.copy(preferredSources = s.preferredSources.toMutableList().apply { add(i - 1, removeAt(i)) })
    }
}
