package com.lagradost.cloudstream4.desktop.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import com.lagradost.cloudstream4.desktop.AndroidHost

/**
 * Brightness is set for the app's window only, like video apps do, so the phone's own setting
 * is untouched. Volume is the phone's media volume, so the volume buttons stay in step.
 */
actual object DeviceControls {
    private val audio get() = AndroidHost.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    actual fun brightness(): Float? {
        val window = AndroidHost.activity?.window ?: return null
        val current = window.attributes.screenBrightness
        if (current >= 0f) return current
        // Following the system: start from the phone's brightness setting
        val system = runCatching { Settings.System.getInt(AndroidHost.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        // Most phones use 0-255, some a larger range: never start above full
        return ((system ?: 128) / 255f).coerceIn(0.01f, 1f)
    }

    actual fun setBrightness(value: Float) {
        val window = AndroidHost.activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
    }

    actual fun resetBrightness() {
        val window = AndroidHost.activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    actual fun volume(): Float? = runCatching {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }.getOrNull()

    actual fun setVolume(value: Float) {
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value.coerceIn(0f, 1f) * max).let { kotlin.math.round(it).toInt() }, 0)
        }
    }
}
