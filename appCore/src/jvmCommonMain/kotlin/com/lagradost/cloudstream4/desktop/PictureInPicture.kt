package com.lagradost.cloudstream4.desktop

import kotlinx.coroutines.flow.MutableStateFlow

/** Android phones and tablets: the player is in its small floating window, so it shows only the video. */
object PictureInPicture {
    val active = MutableStateFlow(false)
}
