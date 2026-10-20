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
    /** Highest quality picked automatically, in pixels of height. Null means best available. */
    @JsonProperty("preferredQuality") val preferredQuality: Int? = null,
    @JsonProperty("autoPlayNext") val autoPlayNext: Boolean = true,
    /** Look up intro, recap and credit times online and show skip buttons, like the Android app. */
    @JsonProperty("skipButtons") val skipButtons: Boolean = true,
    /** Jump over intros and recaps without pressing the button. */
    @JsonProperty("autoSkipIntros") val autoSkipIntros: Boolean = false,
    /** Start with the first source found instead of waiting for all of them. The rest load in the background. */
    @JsonProperty("playFirstSource") val playFirstSource: Boolean = false,
    @JsonProperty("seekSeconds") val seekSeconds: Int = 10,
    @JsonProperty("volume") val volume: Int = 100,
    /** Providers left out of the combined search. */
    @JsonProperty("searchExcluded") val searchExcluded: Set<String> = emptySet(),
    /** Built-in provider with open movies, for trying the app without extensions. */
    @JsonProperty("demoProvider") val demoProvider: Boolean = false,
    /** Where downloads are saved. Null means ~/Movies/CloudStream. */
    @JsonProperty("downloadFolder") val downloadFolder: String? = null,
    /** Connections per download for hosts that allow it, like Internet Download Manager. */
    @JsonProperty("downloadConnections") val downloadConnections: Int = 8,
    /** Connections used to stream one video, fetching ahead in parallel. 1 turns it off. */
    @JsonProperty("streamConnections") val streamConnections: Int = 16,
    /** Files downloading at the same time. The rest wait and start in order as others finish. */
    @JsonProperty("parallelDownloads") val parallelDownloads: Int = 2,
)

object SettingsStore {
    private val file = jsonFile<AppSettings>("settings.json") { AppSettings() }
    private val _state = MutableStateFlow(file.read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(block: (AppSettings) -> AppSettings) {
        _state.update(block)
        file.write(_state.value)
    }
}
