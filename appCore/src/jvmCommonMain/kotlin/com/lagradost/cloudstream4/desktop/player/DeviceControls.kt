package com.lagradost.cloudstream4.desktop.player

/**
 * The device's own screen brightness and media volume, which the player's swipe gestures
 * change on phones and tablets. Desktop leaves both to the system, so there they are null.
 */
expect object DeviceControls {
    /** This app's screen brightness, 0 to 1, or null where the app can't set it. */
    fun brightness(): Float?
    fun setBrightness(value: Float)

    /** Gives brightness back to the system setting, when leaving the player. */
    fun resetBrightness()

    /** The media volume, 0 to 1, or null where the app doesn't control it. */
    fun volume(): Float?
    fun setVolume(value: Float)
}
