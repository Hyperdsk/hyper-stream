package com.lagradost.cloudstream4.desktop.plugins

import com.fasterxml.jackson.annotation.JsonProperty

/** Same JSON shapes as the Android app, so the same repository links work on both. */
data class RepositoryData(
    @JsonProperty("iconUrl") val iconUrl: String? = null,
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
)

data class Repository(
    @JsonProperty("iconUrl") val iconUrl: String? = null,
    @JsonProperty("name") val name: String,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("manifestVersion") val manifestVersion: Int = 1,
    @JsonProperty("pluginLists") val pluginLists: List<String> = emptyList(),
)

/**
 * Status int as the following:
 * 0: Down, 1: Ok, 2: Slow, 3: Beta only
 */
data class SitePlugin(
    @JsonProperty("url") val url: String,
    @JsonProperty("status") val status: Int = 1,
    @JsonProperty("version") val version: Int = 0,
    @JsonProperty("apiVersion") val apiVersion: Int = 1,
    @JsonProperty("name") val name: String,
    @JsonProperty("internalName") val internalName: String,
    @JsonProperty("authors") val authors: List<String> = emptyList(),
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("repositoryUrl") val repositoryUrl: String? = null,
    @JsonProperty("tvTypes") val tvTypes: List<String>? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("iconUrl") val iconUrl: String? = null,
    @JsonProperty("fileSize") val fileSize: Long? = null,
    @JsonProperty("fileHash") val fileHash: String? = null,
)

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
