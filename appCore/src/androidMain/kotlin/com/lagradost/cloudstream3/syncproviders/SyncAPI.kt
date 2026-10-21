package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.utils.UiText

/**
 * The CloudStream app's account sync (AniList, MyAnimeList, Simkl) as extensions see it. This app
 * has no such accounts, so every account reads as signed out and extensions skip those features.
 */
abstract class SyncAPI {
    data class LibraryList(val name: UiText, val items: List<Any>)
    data class LibraryMetadata(val allLibraryLists: List<LibraryList>, val supportedListSorting: Set<Any> = emptySet())
}

/** A signed-in account. Never created here. */
data class AuthUser(val name: String?, val id: Int, val profilePicture: String? = null, val profilePictureHeaders: Map<String, String>? = null)

/** An account sync service; never signed in in this app. */
class SyncRepo(val api: SyncAPI) {
    fun authUser(): AuthUser? = null
}

class AccountManager {
    companion object {
        val simklApi = com.lagradost.cloudstream3.syncproviders.providers.SimklApi()
        val aniListApi = com.lagradost.cloudstream3.syncproviders.providers.AniListApi()
        val malApi = com.lagradost.cloudstream3.syncproviders.providers.MALApi()
    }
}
