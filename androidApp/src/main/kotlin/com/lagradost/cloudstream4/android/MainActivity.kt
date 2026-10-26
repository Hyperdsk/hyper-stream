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

    /** The system folder picker, used to change the download folder or move downloads. */
    private val folderPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        AndroidHost.folderPicked(uri)
    }

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
        // Reopened from Recents, Android hands back the intent that first started the app: don't
        // replay its card or sign-in link
        val fromRecents = (intent?.flags ?: 0) and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromRecents) pendingResume.value = WatchNext.parse(intent?.data)
        if (savedInstanceState == null && !fromRecents) signInFrom(intent?.data)
        AndroidHost.attach(this)
        AndroidHost.openFolderPicker = { folderPicker.launch(null) }
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
            // One collector for the whole activity. Keying an effect on the link and clearing it
            // restarted the effect, cancelling the wait for the extension on a cold start
            LaunchedEffect(Unit) {
                pendingResume.collect { link ->
                    val (api, url) = link ?: return@collect
                    pendingResume.value = null
                    val history = LibraryStore.state.value.history
                    val entry = history.filter { it.apiName == api && it.parentUrl == url }.maxByOrNull { it.updatedAt } ?: return@collect
                    // A TV starting cold loads all its extensions first, which can take longer than a
                    // fixed wait: wait for this one, or until every extension has finished loading
                    if (providerByName(api) == null) com.lagradost.cloudstream4.desktop.DesktopToasts.show("Opening ${entry.name}…")
                    val loaded = withTimeoutOrNull(120_000) {
                        while (providerByName(api) == null && !extensionsSettled()) delay(250)
                        providerByName(api) != null
                    } == true
                    nav.open(Route.Home)
                    // The extension isn't there (removed, or failed to load): say so rather than open a
                    // player that can only fail
                    if (loaded) nav.open(Route.Player(entry.resumeRequest()))
                    else com.lagradost.cloudstream4.desktop.DesktopToasts.show("The $api extension isn't loaded, so ${entry.name} can't play yet.")
                }
            }

            // Back leaves the current screen, then goes to Home, then closes the app
            BackHandler(enabled = nav.stack.size > 1 || nav.current != Route.Home) {
                if (!nav.back()) nav.open(Route.Home)
            }

            // The player fills the screen: no system bars, and landscape on phones and tablets
            val inPlayer = nav.current is Route.Player
            DisposableEffect(inPlayer) {
                playerShown = inPlayer
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

    override fun onResume() {
        super.onResume()
        // Access to Downloads may have been granted in system settings while the app was away
        AndroidHost.storageChanged()
    }

    /** Every installed extension has finished loading, or failed to. */
    private fun extensionsSettled(): Boolean {
        val state = com.lagradost.cloudstream4.desktop.plugins.ExtensionManager.state.value
        return state.installed.isNotEmpty() && state.installed.all {
            val status = state.status[it.filePath]
            status is com.lagradost.cloudstream4.desktop.plugins.PluginStatus.Loaded ||
                status is com.lagradost.cloudstream4.desktop.plugins.PluginStatus.Failed
        }
    }

    /** Whether the player is on screen, for picture-in-picture when the user leaves. */
    private var playerShown = false

    /**
     * Leaving the app (Home, Recents) while a video plays: it goes on in a small window, like other
     * video apps. Not on TVs, where leaving pauses as before.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val engine = (application as CloudStreamApplication).engine
        if (Platform.isTv || !playerShown || !engine.state.value.playing) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(engine.aspectRatio()).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.lagradost.cloudstream4.desktop.player.PictureInPicture.active.value = isInPictureInPictureMode
        // Extensions read this from the app's activity, as in CloudStream
        com.lagradost.cloudstream3.CommonActivity.isInPIPMode = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        (application as CloudStreamApplication).engine.inForeground = true
        (application as CloudStreamApplication).engine.unpark()
        com.lagradost.cloudstream4.desktop.sync.SyncManager.foreground = true
    }

    override fun onDestroy() {
        AndroidHost.openFolderPicker = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // Pause when the app goes to the background, like the Android app, and keep a video that
        // opens meanwhile (its links were still loading) from starting
        val engine = (application as CloudStreamApplication).engine
        engine.inForeground = false
        com.lagradost.cloudstream4.desktop.sync.SyncManager.foreground = false
        if (engine.state.value.playing) engine.togglePause()
        engine.park()
    }
}
