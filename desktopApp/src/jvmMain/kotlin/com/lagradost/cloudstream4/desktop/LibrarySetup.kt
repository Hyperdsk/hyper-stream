package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.LoadResponse

/**
 * Global library settings the Android app sets at startup (AccountManager), which the desktop
 * app has to set too.
 *
 * The id prefixes default to "", so without this an extension's MyAnimeList id and its
 * IMDb/TMDB ids (kept as JSON under the Simkl key) land in the same syncData entry and overwrite
 * each other. That breaks every id lookup, such as skip times by MyAnimeList or IMDb id.
 */
object LibrarySetup {
    fun init() {
        LoadResponse.malIdPrefix = "mal"
        LoadResponse.kitsuIdPrefix = "kitsu"
        LoadResponse.aniListIdPrefix = "anilist"
        LoadResponse.simklIdPrefix = "simkl"
    }
}
