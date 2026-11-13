package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream4.desktop.plugins.PluginLoader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test

/**
 * Loads extension files and walks each provider like a user: home page, open the first title,
 * find its links. Prints each step's result or error. Opt in, it uses the network:
 * HYPER_EXT_FILES = .cs3 paths separated by ':'; HYPER_REAL_DATA = a data folder.
 */
class RealExtensionCheck {
    @Test
    fun providersOpenTitles() {
        val files = System.getenv("HYPER_EXT_FILES")?.split(':')?.filter { it.isNotBlank() } ?: return println("Skipping, set HYPER_EXT_FILES")
        System.getenv("HYPER_REAL_DATA")?.let { System.setProperty("cloudstream.dataDir", it) }
        files.forEach { path ->
            val loaded = runCatching { PluginLoader.load(File(path)) }
            println("PLUGIN ${File(path).name}: " + (loaded.exceptionOrNull()?.let { "load failed: ${it.describe()}" } ?: "loaded"))
        }
        fun step(name: String, block: suspend () -> String) {
            val r = runCatching { runBlocking { withTimeout(45_000) { block() } } }
            println("  $name: " + (r.getOrNull() ?: "ERROR ${r.exceptionOrNull()!!.describe()}"))
        }
        val providers = APIHolder.allProviders.filter { p -> files.any { p.sourcePlugin?.contains(File(it).nameWithoutExtension) == true } }
        println("PROVIDERS ${providers.map { it.name }}")
        for (api in providers) {
            println("PROVIDER ${api.name} (${api.mainUrl})")
            var first: com.lagradost.cloudstream3.SearchResponse? = null
            step("home") {
                val page = api.mainPage.firstOrNull() ?: return@step "no home page"
                val items = api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))?.items?.flatMap { it.list }.orEmpty()
                first = items.firstOrNull()
                "${items.size} items, first '${first?.name}'"
            }
            val item = first ?: continue
            var data: String? = null
            step("open '${item.name}'") {
                val r = api.load(item.url) ?: return@step "null response"
                data = when (r) {
                    is MovieLoadResponse -> r.dataUrl
                    is LiveStreamLoadResponse -> r.dataUrl
                    is TvSeriesLoadResponse -> r.episodes.firstOrNull()?.data
                    is AnimeLoadResponse -> r.episodes.values.firstOrNull()?.firstOrNull()?.data
                    else -> null
                }
                "${r::class.simpleName}, play data ${if (data != null) "found" else "missing"}"
            }
            val d = data ?: continue
            step("links") {
                val links = mutableListOf<ExtractorLink>()
                api.loadLinks(d, false, {}, { links += it })
                "${links.size} links ${links.take(4).map { "${it.name} ${it.type}" }}"
            }
        }
    }

    private fun Throwable.describe(): String =
        "${this::class.qualifiedName}: ${message?.take(200)}" + (stackTrace.take(6).joinToString("") { "\n      at $it" }) +
            (cause?.let { "\n    caused by ${it::class.qualifiedName}: ${it.message?.take(160)}" + it.stackTrace.take(4).joinToString("") { s -> "\n      at $s" } } ?: "")
}
