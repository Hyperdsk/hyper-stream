package com.lagradost.cloudstream4.android

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lagradost.cloudstream4.compose.LocalFocusOutlineDefault
import com.lagradost.cloudstream4.desktop.AndroidHost
import com.lagradost.cloudstream4.desktop.Platform
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.ui.CloudStreamApp
import com.lagradost.cloudstream4.desktop.ui.ModelStore
import com.lagradost.cloudstream4.desktop.ui.Route
import com.lagradost.cloudstream4.desktop.ui.TvTypography
import com.lagradost.cloudstream4.desktop.ui.rememberNavigator
import com.lagradost.cloudstream4.theme.CloudStreamTheme

/**
 * The only activity. An AppCompatActivity, because extensions get it as their context and many
 * cast it to one. Everything inside is the shared Compose app.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidHost.attach(this)
        enableEdgeToEdge()
        val app = application as CloudStreamApplication
        app.startExtensions()
        val engine = app.engine

        setContent {
            val nav = rememberNavigator()
            val store = remember { ModelStore() }
            val settings by SettingsStore.state.collectAsState()

            // Back leaves the current screen, then goes to Home, then closes the app
            BackHandler(enabled = nav.stack.size > 1 || nav.current != Route.Home) {
                if (!nav.back()) nav.open(Route.Home)
            }

            // The player fills the screen: no system bars, and landscape on phones and tablets
            val inPlayer = nav.current is Route.Player
            DisposableEffect(inPlayer) {
                immersive(inPlayer)
                onDispose { }
            }

            CloudStreamTheme(mode = settings.themeMode, primaryColor = settings.primaryColor) {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = TvTypography) {
                    // Remotes move focus, so TVs show where it is
                    CompositionLocalProvider(LocalFocusOutlineDefault provides Platform.isTv) {
                        Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                            CloudStreamApp(nav, store, engine)
                        }
                    }
                }
            }
        }
    }

    private fun immersive(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            if (!Platform.isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (!Platform.isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onStop() {
        super.onStop()
        // Pause when the app goes to the background, like the Android app
        val engine = (application as CloudStreamApplication).engine
        if (engine.state.value.playing) engine.togglePause()
    }
}
