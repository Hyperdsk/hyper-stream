package com.lagradost.cloudstream4.desktop.player

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Android's picture-in-picture: the video keeps playing in a small window over other apps. The
 * activity sets [active]; the player then shows only the picture, with no controls.
 */
object PictureInPicture {
    val active = MutableStateFlow(false)
}
