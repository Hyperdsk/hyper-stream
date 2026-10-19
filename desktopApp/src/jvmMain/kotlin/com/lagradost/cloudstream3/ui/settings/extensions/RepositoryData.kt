package com.lagradost.cloudstream3.ui.settings.extensions

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A repository the user added. Same package, name and constructors as the Android app, so
 * extensions that add repositories (such as MegaRepo's MegaProvider) link against it.
 */
data class RepositoryData(
    @JsonProperty("iconUrl") val iconUrl: String?,
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
) {
    constructor(name: String, url: String) : this(null, name, url)
}
