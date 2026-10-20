package com.lagradost.cloudstream4.desktop.plugins

import com.fasterxml.jackson.annotation.JsonProperty

/** An extension that is downloaded on this machine. */
data class InstalledPlugin(
    @JsonProperty("internalName") val internalName: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("version") val version: Int,
    @JsonProperty("url") val url: String,
    @JsonProperty("repositoryUrl") val repositoryUrl: String?,
    @JsonProperty("iconUrl") val iconUrl: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("tvTypes") val tvTypes: List<String>? = null,
    @JsonProperty("filePath") val filePath: String,
)
