package com.harsh.shah.saavnmp3.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.harsh.shah.saavnmp3.R
import com.harsh.shah.saavnmp3.utils.TrackDownloader

class TrackDownloadService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var isForeground = false

    companion object {
        private const val ACTION_START = "com.harsh.shah.saavnmp3.ACTION_START_DOWNLOAD_SERVICE"
        private const val ACTION_STOP = "com.harsh.shah.saavnmp3.ACTION_STOP_DOWNLOAD_SERVICE"
        private const val EXTRA_TITLE = "extra_title"

        fun start(context: Context, title: String) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, TrackDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, TrackDownloadService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                appContext.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (!isForeground) {
                    startForegroundWithNotification("Downloading...")
                }
                releaseWakeLock()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                isForeground = false
                stopSelf()
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Downloading..."
                startForegroundWithNotification(title)
                acquireWakeLock()
                if (!TrackDownloader.isDownloadActive) {
                    releaseWakeLock()
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                    isForeground = false
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        isForeground = false
        super.onDestroy()
    }

    private fun startForegroundWithNotification(title: String) {
        TrackDownloader.createDownloadNotificationChannel(this)
        val notification = NotificationCompat.Builder(this, TrackDownloader.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_download_for_offline_24)
            .setContentTitle(title)
            .setContentText("Preparing download...")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                TrackDownloader.getCancelPendingIntent(this)
            )
            .build()

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            TrackDownloader.DOWNLOAD_NOTIFICATION_ID,
            notification,
            foregroundType
        )
        isForeground = true
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Melotune::TrackDownloadWakeLock").apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L) // 2 hour safety timeout
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }
}
