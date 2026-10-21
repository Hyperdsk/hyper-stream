package com.lagradost.cloudstream4.desktop.player

/** Desktop: the window has a volume slider, and the screen's brightness belongs to the system. */
actual object DeviceControls {
    actual fun brightness(): Float? = null
    actual fun setBrightness(value: Float) = Unit
    actual fun resetBrightness() = Unit
    actual fun volume(): Float? = null
    actual fun setVolume(value: Float) = Unit
}
