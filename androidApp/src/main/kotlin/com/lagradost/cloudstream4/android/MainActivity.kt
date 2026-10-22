package com.lagradost.cloudstream4.android

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import com.lagradost.cloudstream4.desktop.ui.resumeRequest
import com.lagradost.cloudstream4.desktop.ui.providerByName
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.WatchNext
import androidx.compose.runtime.LaunchedEffect
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
import com.lagradost.cloudstream4.desktop.ui.HyperStreamTheme
import com.lagradost.cloudstream4.desktop.ui.rememberNavigator

/**
 * The only activity. An AppCompatActivity, because extensions get it as their context and many
 * cast it to one. Everything inside is the shared Compose app.
 */
class MainActivity : AppCompatActivity() {
    /** A title to resume, from a Continue watching card on the TV home screen: (provider, page url). */
    private val pendingResume = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        WatchNext.parse(intent.data)?.let { pendingResume.value = it }
        signInFrom(intent.data)
    }

    /** An emailed sign-in link opened hyperstream://auth: finish signing in to sync, and show it in Settings. */
    private fun signInFrom(uri: android.net.Uri?) {
        if (uri?.scheme != WatchNext.SCHEME || uri.host != "auth") return
        com.lagradost.cloudstream4.desktop.sync.SyncManager.completeRedirectLater(uri.toString())
        openSettings.value = true
    }

    private val openSettings = kotlinx.coroutines.flow.MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingResume.value = WatchNext.parse(intent?.data)
        if (savedInstanceState == null) signInFrom(intent?.data)
        AndroidHost.attach(this)
        enableEdgeToEdge()
        // Volume buttons change the media volume, the same one the player's swipe changes
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        val app = application as CloudStreamApplication
        app.startExtensions()
        val engine = app.engine

        setContent {
            val nav = rememberNavigator()
            val store = remember { ModelStore() }
            // While downloads run, a service keeps the app alive in the background, with a
            // notification (asked for once, the first time a download starts)
            val downloads by com.lagradost.cloudstream4.desktop.download.DownloadManager.entries.collectAsState()
            val downloading = downloads.any {
                it.status == com.lagradost.cloudstream4.desktop.download.DownloadStatus.Downloading ||
                    it.status == com.lagradost.cloudstream4.desktop.download.DownloadStatus.Queued
            }
            LaunchedEffect(downloading) {
                if (!downloading) return@LaunchedEffect
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                DownloadService.startIfNeeded(this@MainActivity)
            }
            // The activity is closing for good (not just turning): close its screens' models too,
            // or an old player screen keeps saving progress and reacting to the shared player
            DisposableEffect(store) {
                onDispose { if (!isChangingConfigurations) store.disposeAll() }
            }
            val settings by SettingsStore.state.collectAsState()

            val showSettings by openSettings.collectAsState()
            LaunchedEffect(showSettings) {
                if (showSettings) {
                    openSettings.value = false
                    nav.open(Route.Settings)
                }
            }

            // Opened from the home screen's Continue watching: play from where it stopped, once
            // the title's extension has loaded
            val resume by pendingResume.collectAsState()
            LaunchedEffect(resume) {
                val (api, url) = resume ?: return@LaunchedEffect
                pendingResume.value = null
                val history = LibraryStore.state.value.history
                val entry = history.filter { it.apiName == api && it.parentUrl == url }.maxByOrNull { it.updatedAt } ?: return@LaunchedEffect
                withTimeoutOrNull(20_000) { while (providerByName(api) == null) delay(250) }
                nav.open(Route.Home)
                nav.open(Route.Player(entry.resumeRequest()))
            }

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

            HyperStreamTheme(mode = settings.themeMode, primaryColor = settings.primaryColor) {
                // Remotes move focus, so TVs show where it is
                CompositionLocalProvider(LocalFocusOutlineDefault provides Platform.isTv) {
                    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                        CloudStreamApp(nav, store, engine)
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

    override fun onStart() {
        super.onStart()
        (application as CloudStreamApplication).engine.inForeground = true
    }

    override fun onStop() {
        super.onStop()
        // Pause when the app goes to the background, like the Android app, and keep a video that
        // opens meanwhile (its links were still loading) from starting
        val engine = (application as CloudStreamApplication).engine
        engine.inForeground = false
        if (engine.state.value.playing) engine.togglePause()
    }
}
