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
    @JsonProperty("seekSeconds") val seekSeconds: Int = 10,
    @JsonProperty("volume") val volume: Int = 100,
    /** Providers left out of the combined search. */
    @JsonProperty("searchExcluded") val searchExcluded: Set<String> = emptySet(),
    /** Built-in provider with open movies, for trying the app without extensions. */
    @JsonProperty("demoProvider") val demoProvider: Boolean = false,
    /** Where downloads are saved. Null means ~/Movies/CloudStream. */
    @JsonProperty("downloadFolder") val downloadFolder: String? = null,
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
