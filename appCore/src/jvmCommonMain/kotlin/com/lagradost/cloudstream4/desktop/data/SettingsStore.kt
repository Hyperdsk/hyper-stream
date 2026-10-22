package com.lagradost.cloudstream4.desktop.data

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream4.theme.CloudStreamPrimaryColor
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppSettings(
    @JsonProperty("themeMode") val themeMode: CloudStreamThemeMode = CloudStreamThemeMode.Dark,
    @JsonProperty("primaryColor") val primaryColor: CloudStreamPrimaryColor = CloudStreamPrimaryColor.NORMAL,
    /** Provider name shown on the home screen. */
    @JsonProperty("homeProvider") val homeProvider: String? = null,
    /** The extension each media page (Movies, TV Shows, Anime) shows, by page name. */
    @JsonProperty("sectionProviders") val sectionProviders: Map<String, String> = emptyMap(),
    /** Highest quality picked automatically, in pixels of height. Null means best available. */
    @JsonProperty("preferredQuality") val preferredQuality: Int? = null,
    /** Servers to try first, in order, matched against a source's provider or server name. */
    @JsonProperty("preferredSources") val preferredSources: List<String> = emptyList(),
    /** Servers to try only when nothing else is left. */
    @JsonProperty("avoidedSources") val avoidedSources: List<String> = emptyList(),
    /** "quality": best quality first, preferred servers break ties. "servers": preferred servers first. */
    @JsonProperty("sourceOrder") val sourceOrder: String = "quality",
    /** Don't start early with a source that misses the preferences; wait up to 30 s for one that matches. */
    @JsonProperty("waitForPreferred") val waitForPreferred: Boolean = true,
    @JsonProperty("autoPlayNext") val autoPlayNext: Boolean = true,
    /** Look up intro, recap and credit times online and show skip buttons, like the Android app. */
    @JsonProperty("skipButtons") val skipButtons: Boolean = true,
    /** Jump over intros and recaps without pressing the button. */
    @JsonProperty("autoSkipIntros") val autoSkipIntros: Boolean = false,
    /** The subtitle language picked last ("english"), shown by itself on the next video; null is off. */
    @JsonProperty("subtitleLanguage") val subtitleLanguage: String? = null,
    /** Start with the first source found instead of waiting for all of them. The rest load in the background. */
    @JsonProperty("playFirstSource") val playFirstSource: Boolean = false,
    @JsonProperty("seekSeconds") val seekSeconds: Int = 10,
    @JsonProperty("volume") val volume: Int = 100,
    /** Providers left out of the combined search. */
    @JsonProperty("searchExcluded") val searchExcluded: Set<String> = emptySet(),
    /** Media types search is limited to, by filter name ("Movies", "Anime"...). Empty searches everything. */
    @JsonProperty("searchTypes") val searchTypes: Set<String> = emptySet(),
    /** Built-in provider with open movies, for trying the app without extensions. */
    @JsonProperty("demoProvider") val demoProvider: Boolean = false,
    /** Where downloads are saved. Null means the platform default: ~/Movies/CloudStream, or Downloads/hyperstream on Android. */
    @JsonProperty("downloadFolder") val downloadFolder: String? = null,
    /** Connections per download for hosts that allow it, like Internet Download Manager. */
    @JsonProperty("downloadConnections") val downloadConnections: Int = 8,
    /** Connections used to stream one video, fetching ahead in parallel. 1 turns it off. */
    @JsonProperty("streamConnections") val streamConnections: Int = 16,
    /** Files downloading at the same time. The rest wait and start in order as others finish. */
    @JsonProperty("parallelDownloads") val parallelDownloads: Int = 2,
    /** How the picture fills the screen: a VideoFit name. Kept between videos, like the Android app's resize mode. */
    @JsonProperty("videoFit") val videoFit: String = "Fit",
    /** Touch screens: swipe up and down on the left of the player for brightness, on the right for volume. */
    @JsonProperty("swipeControls") val swipeControls: Boolean = true,
    /** Look for a new release on GitHub, and mention it when one is found. */
    @JsonProperty("checkForUpdates") val checkForUpdates: Boolean = true,
    /** When the last successful update check happened, so automatic checks don't run too often. */
    @JsonProperty("lastUpdateCheckAt") val lastUpdateCheckAt: Long = 0,
    /** The newest version already announced, so one release is mentioned only once. */
    @JsonProperty("lastUpdateNotice") val lastUpdateNotice: String? = null,
)

object SettingsStore {
    private val file = jsonFile<AppSettings>("settings.json") { AppSettings() }
    private val _state = MutableStateFlow(file.read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(block: (AppSettings) -> AppSettings) {
        _state.update(block)
        file.writeLatest { _state.value }
    }
}
