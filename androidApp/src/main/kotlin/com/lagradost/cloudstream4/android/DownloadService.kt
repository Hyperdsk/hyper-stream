package com.lagradost.cloudstream4.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lagradost.cloudstream4.desktop.download.DownloadEntry
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.download.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Keeps the app running while downloads are in progress, with a notification showing them.
 * Without it Android stops a backgrounded app within minutes (sooner on TVs short of memory),
 * and downloads stopped until the app was opened again. Stops itself when nothing is downloading.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION, notification(DownloadManager.entries.value.active()),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            // Android refuses once today's background time is used up (see onTimeout): downloads
            // then go on only while the app is open
            stopSelf()
            return
        }
        scope.launch {
            DownloadManager.entries
                .distinctUntilChangedBy { list -> list.active().map { it.id to ((it.progress ?: 0f) * 100).toInt() } }
                .collect { list ->
                    val active = list.active()
                    if (active.isEmpty()) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        manager.notify(NOTIFICATION, notification(active))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) DownloadManager.pauseEverything()
        return START_NOT_STICKY
    }

    /**
     * Android 15 and later give a data sync service 6 hours a day, then call this, and close the
     * app if the service doesn't stop within seconds. Pause the downloads, say why, and stop.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadManager.pauseEverything()
        getSystemService(NotificationManager::class.java).notify(
            PAUSED_NOTIFICATION,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Downloads paused")
                .setContentText("Android allows downloads in the background for 6 hours a day. Open Hyper Stream to go on.")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(active: List<DownloadEntry>): Notification {
        val first = active.firstOrNull()
        val title = when (active.size) {
            0 -> "Downloads"
            1 -> "Downloading " + listOfNotNull(first?.title, first?.episodeName).joinToString(" · ")
            else -> "Downloading ${active.size} videos"
        }
        val progress = first?.progress
        val pause = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, if (active.size > 1) "Pause all" else "Pause", pause)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 1001
        private const val PAUSED_NOTIFICATION = 1002
        private const val ACTION_PAUSE = "com.lagradost.cloudstream4.android.PAUSE_DOWNLOADS"

        private fun List<DownloadEntry>.active() =
            filter { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }

        /** Starts the service when downloads are running. Call while the app is on screen. */
        fun startIfNeeded(context: Context) {
            if (DownloadManager.entries.value.active().isEmpty()) return
            // Android may refuse when the app isn't in front; downloads then go on as before
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
    }
}
