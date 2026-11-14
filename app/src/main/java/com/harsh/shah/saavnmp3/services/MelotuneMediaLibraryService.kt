package com.harsh.shah.saavnmp3.services

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.harsh.shah.saavnmp3.R
import com.harsh.shah.saavnmp3.activities.MainActivity
import com.harsh.shah.saavnmp3.activities.MusicOverviewActivity
import com.harsh.shah.saavnmp3.automotive.MediaLibraryBrowser
import com.harsh.shah.saavnmp3.automotive.MediaLibraryRepository
import com.harsh.shah.saavnmp3.utils.MusicPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MelotuneMediaLibraryService : MediaLibraryService() {

    companion object {
        private const val TAG = "MelotuneMediaService"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var repository: MediaLibraryRepository
    private lateinit var browser: MediaLibraryBrowser

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        MusicPlayerManager.init(applicationContext)

        repository = MediaLibraryRepository(applicationContext)
        browser = MediaLibraryBrowser(repository)

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(MusicPlayerManager.CHANNEL_ID_1)
            .setChannelName(R.string.app_name)
            .build().apply {
                setSmallIcon(R.drawable.headphone)
            }
        setMediaNotificationProvider(notificationProvider)

        val basePlayer = MusicPlayerManager.player
            ?: throw IllegalStateException("ExoPlayer failed to initialize")

        val forwardingPlayer = MelotuneForwardingPlayer(basePlayer)

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            forwardingPlayer,
            MelotuneLibrarySessionCallback()
        )
            .setSessionActivity(sessionActivityIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundNotificationStarted()
        val result = super.onStartCommand(intent, flags, startId)
        mediaLibrarySession?.let { session ->
            try {
                onUpdateNotification(session, true)
            } catch (e: Exception) {
                Log.w(TAG, "onUpdateNotification in onStartCommand failed: ${e.message}")
            }
        }
        return result
    }

    private fun ensureForegroundNotificationStarted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val notification: Notification = NotificationCompat.Builder(this, MusicPlayerManager.CHANNEL_ID_1)
                .setSmallIcon(R.drawable.headphone)
                .setContentTitle(MusicPlayerManager.MUSIC_TITLE?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name))
                .setContentText(MusicPlayerManager.MUSIC_DESCRIPTION?.takeIf { it.isNotBlank() } ?: "Playing audio")
                .setOngoing(true)
                .setSilent(true)
                .build()
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                notification,
                fgsType
            )
        } catch (e: Exception) {
            Log.w(TAG, "ensureForegroundNotificationStarted failed: ${e.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val playInBackground = prefs.getBoolean("play_in_background", true)
        val player = MusicPlayerManager.player
        if (!playInBackground || player == null || !player.playWhenReady) {
            Log.d(TAG, "onTaskRemoved: stopping playback (playInBackground=$playInBackground)")
            try {
                player?.pause()
                player?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping player on task removed", e)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class MelotuneForwardingPlayer(player: Player) : ForwardingPlayer(player) {

        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands().buildUpon()
                .add(COMMAND_PLAY_PAUSE)
                .add(COMMAND_SEEK_TO_NEXT)
                .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_PREVIOUS)
                .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(COMMAND_SET_SHUFFLE_MODE)
                .add(COMMAND_SET_REPEAT_MODE)
                .add(COMMAND_STOP)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return when (command) {
                COMMAND_PLAY_PAUSE,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SET_SHUFFLE_MODE,
                COMMAND_SET_REPEAT_MODE,
                COMMAND_STOP -> true
                else -> super.isCommandAvailable(command)
            }
        }

        override fun hasNextMediaItem(): Boolean {
            val queue = MusicPlayerManager.trackQueue
            if (!queue.isNullOrEmpty() && MusicPlayerManager.track_position < queue.size - 1) return true
            if (repeatMode == REPEAT_MODE_ALL || repeatMode == REPEAT_MODE_ONE) return true
            return super.hasNextMediaItem()
        }

        override fun hasPreviousMediaItem(): Boolean {
            if (!MusicPlayerManager.trackQueue.isNullOrEmpty() || currentPosition > 3000L) return true
            return super.hasPreviousMediaItem()
        }

        override fun seekToNext() {
            MusicPlayerManager.nextTrack()
        }

        override fun seekToNextMediaItem() {
            MusicPlayerManager.nextTrack()
        }

        override fun seekToPrevious() {
            MusicPlayerManager.prevTrack()
        }

        override fun seekToPreviousMediaItem() {
            MusicPlayerManager.prevTrack()
        }

        override fun play() {
            if (playbackState == STATE_IDLE) {
                if (!MusicPlayerManager.SONG_URL.isNullOrEmpty()) {
                    MusicPlayerManager.prepareMediaPlayer()
                    return
                } else if (!MusicPlayerManager.MUSIC_ID.isNullOrEmpty()) {
                    MusicPlayerManager.playTrack()
                    return
                }
            }
            super.play()
        }
    }

    private inner class MelotuneLibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SET_SHUFFLE_MODE)
                .add(Player.COMMAND_SET_REPEAT_MODE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browserController: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(LibraryResult.ofItem(browser.getRootItem(), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browserController: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    val children = browser.getChildren(parentId, page, pageSize)
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
                } catch (e: Exception) {
                    Log.e(TAG, "onGetChildren failed for $parentId", e)
                    future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browserController: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch {
                try {
                    val item = browser.getItem(mediaId)
                    if (item != null) {
                        future.set(LibraryResult.ofItem(item, null))
                    } else {
                        future.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                    }
                } catch (e: Exception) {
                    future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browserController: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val future = SettableFuture.create<LibraryResult<Void>>()
            serviceScope.launch {
                try {
                    val results = browser.search(query, 0, 25)
                    session.notifySearchResultChanged(browserController, query, results.size, params)
                    future.set(LibraryResult.ofVoid(params))
                } catch (e: Exception) {
                    Log.e(TAG, "onSearch failed for query=$query", e)
                    future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browserController: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    val results = browser.search(query, page, pageSize)
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(results), params))
                } catch (e: Exception) {
                    future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            val firstItem = mediaItems.firstOrNull()
            if (firstItem == null) {
                future.set(mediaItems)
                return future
            }

            serviceScope.launch {
                try {
                    val (queueTracks, startIndex) = browser.resolveQueueForMediaItem(firstItem)
                    val ids = queueTracks.map { it.localFilePath?.takeIf { path -> it.id == path } ?: it.id }
                    val selectedTrack = queueTracks.getOrNull(startIndex)
                    if (selectedTrack?.localFilePath != null) {
                        MusicPlayerManager.trackQueue = ArrayList(ids)
                        MusicPlayerManager.track_position = startIndex
                        MusicPlayerManager.playLocalFile(
                            filePath = selectedTrack.localFilePath,
                            title = selectedTrack.title,
                            artist = selectedTrack.artist,
                            album = selectedTrack.album,
                            trackUid = selectedTrack.id
                        )
                    } else if (ids.isNotEmpty()) {
                        MusicPlayerManager.playFromQueue(ids, startIndex)
                    }
                    future.set(mediaItems)
                } catch (e: Exception) {
                    Log.e(TAG, "onAddMediaItems failed", e)
                    future.set(mediaItems)
                }
            }
            return future
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val targetItem = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
            if (targetItem == null) {
                future.set(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
                return future
            }

            serviceScope.launch {
                try {
                    val (queueTracks, resolvedIdx) = browser.resolveQueueForMediaItem(targetItem)
                    val ids = queueTracks.map { it.localFilePath?.takeIf { path -> it.id == path } ?: it.id }
                    val selectedTrack = queueTracks.getOrNull(resolvedIdx)
                    if (selectedTrack?.localFilePath != null) {
                        MusicPlayerManager.trackQueue = ArrayList(ids)
                        MusicPlayerManager.track_position = resolvedIdx
                        MusicPlayerManager.playLocalFile(
                            filePath = selectedTrack.localFilePath,
                            title = selectedTrack.title,
                            artist = selectedTrack.artist,
                            album = selectedTrack.album,
                            trackUid = selectedTrack.id
                        )
                    } else if (ids.isNotEmpty()) {
                        MusicPlayerManager.playFromQueue(ids, resolvedIdx)
                    }
                    val currentItems = MusicPlayerManager.player?.currentMediaItem?.let { listOf(it) } ?: mediaItems
                    future.set(MediaSession.MediaItemsWithStartPosition(currentItems, 0, startPositionMs))
                } catch (e: Exception) {
                    Log.e(TAG, "onSetMediaItems failed", e)
                    future.set(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
                }
            }
            return future
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val recent = repository.getRecentlyPlayed()
                if (recent.isNotEmpty()) {
                    val ids = recent.map { it.id }
                    MusicPlayerManager.playFromQueue(ids, 0)
                    val item = MusicPlayerManager.player?.currentMediaItem
                    if (item != null) {
                        future.set(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L))
                        return@launch
                    }
                }
                future.setException(UnsupportedOperationException("No recent tracks to resume"))
            }
            return future
        }
    }
}
