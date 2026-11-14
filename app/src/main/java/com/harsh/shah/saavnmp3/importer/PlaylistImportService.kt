package com.harsh.shah.saavnmp3.importer

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.harsh.shah.saavnmp3.R
import com.harsh.shah.saavnmp3.activities.PlaylistImportActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps large YouTube playlist imports alive in the background
 * even when the user navigates away from [PlaylistImportActivity] or locks the screen.
 */
class PlaylistImportService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var lastNotifiedProcessed = -1
    private var lastNotifiedPhase: ImportPhase? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                coordinatorInstance?.cancelImport()
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }

        val coord = getOrCreateCoordinator(applicationContext)
        val initialNotification = buildNotification(coord.uiState.value)
        startForegroundCompat(initialNotification)

        if (observerJob == null || observerJob?.isActive != true) {
            observerJob = serviceScope.launch {
                coord.uiState.collectLatest { state ->
                    when (state.phase) {
                        ImportPhase.FETCHING_PLAYLIST, ImportPhase.MATCHING_SONGS -> {
                            val shouldUpdate = state.phase != lastNotifiedPhase ||
                                state.processedTracks == state.totalTracks ||
                                (state.processedTracks - lastNotifiedProcessed) >= 5
                            if (shouldUpdate) {
                                lastNotifiedPhase = state.phase
                                lastNotifiedProcessed = state.processedTracks
                                val nm = getSystemService(NotificationManager::class.java)
                                nm?.notify(NOTIFICATION_ID, buildNotification(state))
                            }
                        }
                        ImportPhase.COMPLETED, ImportPhase.CANCELLED, ImportPhase.ERROR -> {
                            if (lastNotifiedPhase == ImportPhase.FETCHING_PLAYLIST ||
                                lastNotifiedPhase == ImportPhase.MATCHING_SONGS
                            ) {
                                lastNotifiedPhase = state.phase
                                val nm = getSystemService(NotificationManager::class.java)
                                nm?.notify(COMPLETION_NOTIFICATION_ID, buildCompletionNotification(state))
                            }
                            stopSelfSafely()
                        }
                        ImportPhase.IDLE -> Unit
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to promote PlaylistImportService to foreground: ${e.message}")
        }
    }

    private fun stopSelfSafely() {
        observerJob?.cancel()
        observerJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(state: PlaylistImportUiState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlaylistImportActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaylistImportService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = state.playlistTitle.ifBlank { "Importing YouTube Playlist" }
        val text = when (state.phase) {
            ImportPhase.FETCHING_PLAYLIST -> "Fetching playlist tracks..."
            ImportPhase.MATCHING_SONGS -> "Matching songs (${state.processedTracks}/${state.totalTracks}) • Matched ${state.matchedCount + state.needsReviewCount}"
            else -> "Preparing import..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_download_for_offline_24)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(
                state.totalTracks.coerceAtLeast(1),
                state.processedTracks.coerceAtLeast(0),
                state.phase == ImportPhase.FETCHING_PLAYLIST
            )
            .addAction(0, "Cancel", cancelIntent)
            .build()
    }

    private fun buildCompletionNotification(state: PlaylistImportUiState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlaylistImportActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val readyCount = state.items.count {
            (it.status == ImportItemStatus.MATCHED || it.status == ImportItemStatus.NEEDS_REVIEW) &&
                it.selectedSong != null
        }
        val title = state.playlistTitle.ifBlank { "YouTube Playlist Import" }
        val text = when (state.phase) {
            ImportPhase.COMPLETED -> "Ready to save $readyCount song(s). Tap to review & save."
            ImportPhase.CANCELLED -> "Import cancelled (${state.processedTracks}/${state.totalTracks})."
            ImportPhase.ERROR -> state.errorMessage ?: "Import failed."
            else -> "Import finished."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_download_for_offline_24)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playlist Imports",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background progress for YouTube playlist imports"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "PlaylistImportService"
        private const val CHANNEL_ID = "melotune_playlist_import_channel"
        private const val NOTIFICATION_ID = 3001
        private const val COMPLETION_NOTIFICATION_ID = 3002
        const val ACTION_CANCEL = "com.harsh.shah.saavnmp3.ACTION_CANCEL_PLAYLIST_IMPORT"

        @Volatile
        private var coordinatorInstance: PlaylistImportCoordinator? = null

        fun getOrCreateCoordinator(context: Context): PlaylistImportCoordinator {
            return coordinatorInstance ?: synchronized(this) {
                coordinatorInstance ?: run {
                    val appCtx = context.applicationContext
                    val repository = PlaylistImportRepository(appCtx)
                    val searchDataSource = ApiManagerJioSaavnSearch(appCtx)
                    val matcher = JioSaavnSongMatcher(searchDataSource)
                    PlaylistImportCoordinator(
                        youTubeClient = YouTubePlaylistClient(),
                        jioSaavnSearch = searchDataSource,
                        matcher = matcher,
                        persistence = repository
                    ).also { coordinatorInstance = it }
                }
            }
        }

        fun startImport(context: Context, url: String, apiKeyOverride: String? = null) {
            val coord = getOrCreateCoordinator(context)
            coord.startImport(url, apiKeyOverride)
            startService(context)
        }

        fun retryFailedMatches(context: Context) {
            val coord = getOrCreateCoordinator(context)
            coord.retryFailedMatches()
            startService(context)
        }

        fun cancelImport(context: Context) {
            coordinatorInstance?.cancelImport()
            try {
                context.stopService(Intent(context, PlaylistImportService::class.java))
            } catch (_: Exception) {
            }
        }

        private fun startService(context: Context) {
            val intent = Intent(context.applicationContext, PlaylistImportService::class.java)
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService failed, trying startService: ${e.message}")
                try {
                    context.applicationContext.startService(intent)
                } catch (_: Exception) {
                }
            }
        }
    }
}
