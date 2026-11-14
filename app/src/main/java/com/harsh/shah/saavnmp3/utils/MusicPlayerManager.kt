package com.harsh.shah.saavnmp3.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.ExoDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.palette.graphics.Palette
import com.google.gson.Gson
import com.harsh.shah.saavnmp3.widgets.WidgetCardProvider
import com.harsh.shah.saavnmp3.widgets.WidgetVinylProvider
import com.harsh.shah.saavnmp3.BaseApplicationClass
import com.harsh.shah.saavnmp3.R
import com.harsh.shah.saavnmp3.activities.MusicOverviewActivity
import com.harsh.shah.saavnmp3.network.ApiManager
import com.harsh.shah.saavnmp3.network.utility.RequestNetwork
import com.harsh.shah.saavnmp3.records.SongResponse
import com.harsh.shah.saavnmp3.services.MelotuneMediaLibraryService
import com.harsh.shah.saavnmp3.widgets.WidgetPlayerProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

@OptIn(UnstableApi::class)
object MusicPlayerManager {
    private const val TAG = "MusicPlayerManager"

    const val CHANNEL_ID_1: String = "channel_1"
    const val ACTION_NEXT: String = "next"
    const val ACTION_PREV: String = "prev"
    const val ACTION_PLAY: String = "play"

    var CURRENT_TRACK: SongResponse? = null
    var player: ExoPlayer? = null
    var musicService: com.harsh.shah.saavnmp3.services.MusicService? = null
    var latestNotification: android.app.Notification? = null

    interface PlaybackListener {
        fun onPlaybackStateUpdated()
    }

    private val playbackListeners = CopyOnWriteArraySet<PlaybackListener>()

    fun addPlaybackListener(listener: PlaybackListener) {
        playbackListeners.add(listener)
    }

    fun removePlaybackListener(listener: PlaybackListener) {
        playbackListeners.remove(listener)
    }

    private fun notifyPlaybackListeners() {
        for (listener in playbackListeners) {
            try {
                listener.onPlaybackStateUpdated()
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying playback listener: ${e.message}")
            }
        }
    }

    var TRACK_QUALITY: String? = "320kbps"
    var isTrackDownloaded: Boolean = false
    var trackQueue: MutableList<String?>? = ArrayList()
    var track_position: Int = -1

    var MUSIC_TITLE: String? = ""
    var MUSIC_DESCRIPTION: String? = ""
    var IMAGE_URL: String? = ""
    var MUSIC_ID: String? = ""
    var SONG_URL: String? = ""
    var currentlyPlayingTrackId: String? = null

    var IMAGE_BG_COLOR: Int = Color.argb(255, 25, 20, 20)
    var TEXT_ON_IMAGE_COLOR: Int = IMAGE_BG_COLOR xor 0x00FFFFFF
    var TEXT_ON_IMAGE_COLOR1: Int = IMAGE_BG_COLOR xor 0x00FFFFFF

    private var appContext: Context? = null
    private var simpleCache: SimpleCache? = null
    private var sharedPreferenceManager: SharedPreferenceManager? = null

    var isTransitioning: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoplayRequestId: Long = 0L

    fun acquireWakeLock(timeoutMs: Long = 30000L) {
        try {
            if (wakeLock == null && appContext != null) {
                val pm = appContext!!.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Melotune:PlaybackWakeLock")
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(timeoutMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wakeLock: \${e.message}")
        }
    }

    fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wakeLock: \${e.message}")
        }
    }

    fun cancelAutoplay() {
        autoplayRequestId++
        isFetchingAutoplay = false
    }

    @OptIn(UnstableApi::class)
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        sharedPreferenceManager = SharedPreferenceManager.getInstance(appContext!!)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Util.getUserAgent(appContext!!, "AudioCachingApp"))
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)

        val mediaDataSourceFactory: androidx.media3.datasource.DataSource.Factory = try {
            val cacheDir = File(appContext!!.cacheDir, "audio_cache")
            val databaseProvider = ExoDatabaseProvider(appContext!!)
            val cacheSize = (100 * 1024 * 1024).toLong() // 100 MB
            val cacheEvictor = LeastRecentlyUsedCacheEvictor(cacheSize)
            simpleCache = SimpleCache(cacheDir, cacheEvictor, databaseProvider)
            CacheDataSource.Factory()
                .setCache(simpleCache!!)
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } catch (e: Exception) {
            Log.w(TAG, "SimpleCache init failed, falling back to HTTP data source: ${e.message}")
            httpDataSourceFactory
        }

        player = ExoPlayer.Builder(appContext!!)
            .setMediaSourceFactory(DefaultMediaSourceFactory(mediaDataSourceFactory))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .build()

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.i(TAG, "Player state changed to: ${getStateString(playbackState)}")
                when (playbackState) {
                    Player.STATE_READY -> {
                        isTransitioning = false
                        releaseWakeLock()
                        updateWidget()
                    }
                    Player.STATE_ENDED -> {
                        nextTrack()
                    }
                    else -> {
                        updateWidget()
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.i(TAG, "Player playWhenReady changed to: $playWhenReady (reason: $reason)")
                showNotification()
                updateWidget()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.i(TAG, "Player isPlaying changed to: $isPlaying")
                showNotification()
                updateWidget()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.message}")
                isTransitioning = false
                releaseWakeLock()
                notifyPlaybackListeners()
            }
        })

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_1, "Media Controls",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifications for media playback" }
            val notificationManager = appContext?.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun setTrackQuality(quality: String?, prefs: SharedPreferenceManager? = sharedPreferenceManager) {
        TRACK_QUALITY = quality
        prefs?.trackQuality = quality
    }

    fun setMusicDetails(image: String?, title: String?, description: String?, id: String?) {
        image?.let { IMAGE_URL = it }
        title?.let { MUSIC_TITLE = it }
        description?.let { MUSIC_DESCRIPTION = it }
        MUSIC_ID = id
        Log.i(TAG, "setMusicDetails: $MUSIC_TITLE - ID: $MUSIC_ID")

        if (!image.isNullOrBlank()) {
            val ctx = appContext ?: BaseApplicationClass.currentActivity
            if (ctx != null) {
                try {
                    Glide.with(ctx.applicationContext)
                        .asBitmap()
                        .load(image)
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                if (resource.isRecycled) return
                                try {
                                    Palette.from(resource).generate { palette ->
                                        palette?.let {
                                            val swatch = it.darkMutedSwatch ?: it.darkVibrantSwatch ?: it.dominantSwatch
                                            swatch?.let { s ->
                                                IMAGE_BG_COLOR = s.rgb
                                                TEXT_ON_IMAGE_COLOR = s.titleTextColor
                                                TEXT_ON_IMAGE_COLOR1 = s.bodyTextColor
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Palette generation failed: ${e.message}")
                                }
                            }
                            override fun onLoadCleared(placeholder: Drawable?) {}
                        })
                } catch (e: Exception) {
                    Log.e(TAG, "Palette generation error: ${e.message}")
                }
            }
        }

        updateWidget()
    }

    fun cancelNotification() {
        val notificationManager = appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(1)
    }

    fun updateWidget() {
        appContext?.let {
            WidgetPlayerProvider.updateAllWidgets(it)
            WidgetCardProvider.updateAllWidgets(it)
            WidgetVinylProvider.updateAllWidgets(it)
        }
    }

    fun getDownloadUrl(downloadUrlList: MutableList<SongResponse.DownloadUrl?>?): String {
        if (downloadUrlList.isNullOrEmpty()) return ""
        var bestUrl = ""
        for (downloadUrl in downloadUrlList) {
            val url = downloadUrl?.url ?: continue
            if (url.startsWith("https:") && downloadUrl.quality == TRACK_QUALITY) return url
            if (downloadUrl.quality == TRACK_QUALITY) bestUrl = url
        }
        if (bestUrl.isNotEmpty()) {
            return if (bestUrl.startsWith("http:")) bestUrl.replace("http:", "https:") else bestUrl
        }
        val lastUrl = downloadUrlList.lastOrNull()?.url ?: return ""
        return if (lastUrl.startsWith("http:")) lastUrl.replace("http:", "https:") else lastUrl
    }

    private fun ensureMediaServiceStarted() {
        val ctx = appContext ?: return
        if (MelotuneMediaLibraryService.isRunning) return
        try {
            val intent = Intent(ctx, MelotuneMediaLibraryService::class.java)
            ContextCompat.startForegroundService(ctx, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start MelotuneMediaLibraryService: ${e.message}")
        }
    }

    fun showNotification(playPauseButton: Int = if (isPlayerPlaying()) R.drawable.baseline_pause_24 else R.drawable.play_arrow_24px) {
        updateWidget()
        notifyPlaybackListeners()
    }

    fun isPlayerPlaying(): Boolean {
        val p = player ?: return false
        return p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (isPlayerPlaying()) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) {
                prepareMediaPlayer()
            } else if (p.playbackState == Player.STATE_ENDED) {
                p.seekTo(0)
                p.play()
            } else {
                p.play()
            }
        }

        showNotification()
        updateWidget()
    }

    fun prepareMediaPlayer() {
        val p = player ?: return
        if (p.isPlaying) p.stop()
        p.clearMediaItems()
        if (SONG_URL.isNullOrEmpty()) return
        acquireWakeLock(15000L)

        var finalUrl = SONG_URL ?: ""
        val isLocalFile = finalUrl.startsWith("file://") || finalUrl.startsWith("/")
        if (!isLocalFile && finalUrl.startsWith("http:")) {
            finalUrl = finalUrl.replace("http:", "https:")
        }
        val mediaUri = if (finalUrl.startsWith("/")) Uri.fromFile(File(finalUrl)) else Uri.parse(finalUrl)

        val currentSong = CURRENT_TRACK?.data?.firstOrNull()
        val artistText = currentSong?.artists?.primary?.mapNotNull { it?.name() }?.joinToString(", ")
            ?.takeIf { it.isNotBlank() } ?: MUSIC_DESCRIPTION
        val albumText = currentSong?.album?.name()?.takeIf { it.isNotBlank() }
        val artworkUri = IMAGE_URL?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

        val metadata = MediaMetadata.Builder()
            .setTitle(MUSIC_TITLE ?: "Melotune")
            .setArtist(artistText ?: "Melotune")
            .setAlbumTitle(albumText)
            .setArtworkUri(artworkUri)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaId(MUSIC_ID ?: finalUrl)
            .setMediaMetadata(metadata)
            .build()

        isTrackDownloaded = isLocalFile
        currentlyPlayingTrackId = MUSIC_ID
        p.setMediaItem(mediaItem)
        p.playWhenReady = true
        p.prepare()
        ensureMediaServiceStarted()
        showNotification()
        updateWidget()
    }

    fun playFromQueue(songIds: List<String>, startIndex: Int = 0) {
        if (songIds.isEmpty()) return
        cancelAutoplay()
        val safeIndex = startIndex.coerceIn(0, songIds.lastIndex)
        trackQueue = ArrayList(songIds)
        track_position = safeIndex
        MUSIC_ID = songIds[safeIndex]
        playTrack()
    }

    fun playLocalFile(
        filePath: String,
        title: String,
        artist: String,
        album: String? = null,
        trackUid: String? = null
    ) {
        val file = File(filePath)
        if (!file.exists()) return
        cancelAutoplay()
        MUSIC_ID = trackUid ?: filePath
        MUSIC_TITLE = title
        MUSIC_DESCRIPTION = artist
        SONG_URL = file.absolutePath
        IMAGE_URL = ""
        CURRENT_TRACK = null
        setMusicDetails(IMAGE_URL, MUSIC_TITLE, album?.takeIf { it.isNotBlank() } ?: artist, MUSIC_ID)
        prepareMediaPlayer()
    }

    fun playNext(trackId: String?) {
        if (trackId.isNullOrEmpty()) return
        cancelAutoplay()
        val queue = trackQueue ?: ArrayList()
        queue.remove(trackId)
        
        val insertPos = if (track_position == -1) 0 else track_position + 1
        if (insertPos >= queue.size) {
            queue.add(trackId)
        } else {
            queue.add(insertPos, trackId)
        }
        trackQueue = queue
        
        if (track_position == -1) {
            track_position = 0
            MUSIC_ID = trackId
            playTrack()
        }
        updateWidget()
    }

    fun addToQueue(trackId: String?) {
        if (trackId.isNullOrEmpty()) return
        cancelAutoplay()
        val queue = trackQueue ?: ArrayList()
        if (!queue.contains(trackId)) {
            queue.add(trackId)
        }
        trackQueue = queue
        
        if (track_position == -1) {
            track_position = 0
            MUSIC_ID = trackId
            playTrack()
        }
        updateWidget()
    }

    private fun tryPlayOfflineMatch(ctx: Context, trackId: String): Boolean {
        return try {
            val downloaded = TrackDownloader.getDownloadedTracks(ctx).filterNotNull()
            val match = downloaded.firstOrNull { it.trackUID == trackId || it.file?.absolutePath == trackId }
            val file = match?.file
            if (match != null && file != null && file.exists()) {
                playLocalFile(
                    filePath = file.absolutePath,
                    title = match.title ?: "Downloaded Track",
                    artist = match.artist ?: "Unknown Artist",
                    album = match.album,
                    trackUid = match.trackUID ?: trackId
                )
                isTransitioning = false
                releaseWakeLock()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Offline match lookup failed: ${e.message}")
            false
        }
    }

    fun playTrack() {
        if (MUSIC_ID.isNullOrEmpty()) {
            isTransitioning = false
            releaseWakeLock()
            return
        }
        cancelAutoplay()
        isTransitioning = true
        acquireWakeLock(30000L)

        val ctx = BaseApplicationClass.currentActivity ?: appContext ?: return
        val targetId = MUSIC_ID!!
        if (targetId.startsWith("/") || targetId.startsWith("file://")) {
            if (tryPlayOfflineMatch(ctx, targetId)) return
        }

        ApiManager(ctx).retrieveSongById(targetId, null, object : RequestNetwork.RequestListener {
            override fun onResponse(tag: String?, response: String?, responseHeaders: HashMap<String?, Any?>?) {
                val songResponse = try {
                    Gson().fromJson(response, SongResponse::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse song response for $targetId: ${e.message}")
                    null
                }
                if (songResponse != null && songResponse.success && !songResponse.data.isNullOrEmpty()) {
                    CURRENT_TRACK = songResponse
                    val firstSong = songResponse.data[0] ?: return
                    MUSIC_TITLE = firstSong.name()
                    MUSIC_DESCRIPTION = "${MusicOverviewActivity.convertPlayCount(firstSong.playCount ?: 0)} plays | ${firstSong.year} | ${firstSong.copyright}"
                    firstSong.image?.lastOrNull()?.url?.let { IMAGE_URL = it }
                    SONG_URL = getDownloadUrl(firstSong.downloadUrl)
                    setMusicDetails(IMAGE_URL, MUSIC_TITLE, MUSIC_DESCRIPTION, MUSIC_ID)
                    prepareMediaPlayer()

                    // Record to recently played history
                    val primaryArtist = firstSong.artists?.primary?.firstOrNull()
                    val artistsStr = firstSong.artists?.all?.joinToString(", ") { it?.name() ?: "" } ?: ""
                    sharedPreferenceManager?.addRecentlyPlayed(
                        com.harsh.shah.saavnmp3.records.sharedpref.SavedLibraries.Library.Songs(
                            firstSong.id,
                            firstSong.name(),
                            artistsStr,
                            IMAGE_URL
                        ),
                        artistId = primaryArtist?.id,
                        artistName = primaryArtist?.name()
                    )
                } else {
                    if (!tryPlayOfflineMatch(ctx, targetId)) {
                        isTransitioning = false
                        releaseWakeLock()
                    }
                }
            }
            override fun onErrorResponse(tag: String?, message: String?) {
                if (!tryPlayOfflineMatch(ctx, targetId)) {
                    isTransitioning = false
                    releaseWakeLock()
                }
            }
        })
    }

    private var isFetchingAutoplay = false

    private fun fetchAndAppendAutoplayRecommendations(currentSong: SongResponse.Song) {
        if (isFetchingAutoplay) return
        isFetchingAutoplay = true
        val currentRequestId = ++autoplayRequestId
        val currentSongId = currentSong.id ?: return
        val currentPosSnapshot = track_position
        val ctx = appContext ?: BaseApplicationClass.currentActivity ?: return

        RecommendationManager.getRecommendationsForSong(ctx, currentSong, limit = 10) { recommendedSongs ->
            isFetchingAutoplay = false
            // Verify that this autoplay callback is still valid and wasn't superseded by user selecting a track
            if (currentRequestId != autoplayRequestId) {
                Log.d(TAG, "Autoplay discarded: newer request or user selection occurred")
                return@getRecommendationsForSong
            }
            if (MUSIC_ID != currentSongId || track_position != currentPosSnapshot) {
                Log.d(TAG, "Autoplay discarded: active track or position changed")
                return@getRecommendationsForSong
            }

            if (recommendedSongs.isNotEmpty()) {
                val queue = trackQueue ?: ArrayList()
                val newIds = recommendedSongs.mapNotNull { it.id }.filter { !queue.contains(it) }
                if (newIds.isNotEmpty()) {
                    queue.addAll(newIds)
                    trackQueue = queue
                    // Only advance if the user is still at the end of the previous queue
                    if (track_position == currentPosSnapshot && track_position < queue.size - 1) {
                        track_position++
                        MUSIC_ID = queue[track_position]
                        playTrack()
                        showNotification()
                        updateWidget()
                        appContext?.let { toastCtx ->
                            android.widget.Toast.makeText(toastCtx, "Autoplay: Playing recommended tracks", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        player?.playbackParameters = androidx.media3.common.PlaybackParameters(speed)
    }

    fun getPlaybackSpeed(): Float {
        return player?.playbackParameters?.speed ?: 1.0f
    }

    fun nextTrack() {
        acquireWakeLock(30000L)
        isTransitioning = true

        if (trackQueue.isNullOrEmpty()) {
            if (!MUSIC_ID.isNullOrEmpty()) {
                trackQueue = arrayListOf(MUSIC_ID)
                track_position = 0
            } else {
                isTransitioning = false
                releaseWakeLock()
                return
            }
        }
        val p = player ?: run {
            isTransitioning = false
            releaseWakeLock()
            return
        }

        if (trackQueue!!.isEmpty()) {
            isTransitioning = false
            releaseWakeLock()
            return
        }

        if (track_position >= trackQueue!!.size - 1) {
            when (p.repeatMode) {
                Player.REPEAT_MODE_ONE -> {
                    p.seekTo(0)
                    p.play()
                    isTransitioning = false
                    releaseWakeLock()
                    return
                }
                Player.REPEAT_MODE_ALL -> {
                    track_position = 0
                }
                else -> {
                    // Check Autoplay Recommended Music
                    val prefs = sharedPreferenceManager ?: appContext?.let { SharedPreferenceManager.getInstance(it) }
                    if (prefs?.isAutoplayEnabled == true && CURRENT_TRACK?.data?.isNotEmpty() == true) {
                        val currentSong = CURRENT_TRACK!!.data!![0]
                        if (currentSong != null) {
                            fetchAndAppendAutoplayRecommendations(currentSong)
                            return
                        }
                    }
                    // If no autoplay, restart current track or seek to beginning
                    p.seekTo(0)
                    p.play()
                    isTransitioning = false
                    releaseWakeLock()
                    return
                }
            }
        } else {
            if (p.shuffleModeEnabled && trackQueue!!.size > 1) {
                var newPos: Int
                do { newPos = (Math.random() * trackQueue!!.size).toInt() } while (newPos == track_position)
                track_position = newPos
            } else { track_position++ }
        }
        
        try {
            MUSIC_ID = trackQueue!![track_position]
            playTrack()
            showNotification()
            updateWidget()
        } catch (e: Exception) {
            if (trackQueue!!.isNotEmpty()) {
                track_position = 0
                MUSIC_ID = trackQueue!![0]
                playTrack()
            } else {
                isTransitioning = false
                releaseWakeLock()
            }
        }
    }

    fun prevTrack() {
        acquireWakeLock(30000L)
        isTransitioning = true

        if (trackQueue.isNullOrEmpty()) {
            if (!MUSIC_ID.isNullOrEmpty()) {
                player?.seekTo(0)
                player?.play()
            }
            isTransitioning = false
            releaseWakeLock()
            return
        }
        val p = player ?: run {
            isTransitioning = false
            releaseWakeLock()
            return
        }

        if (p.currentPosition > 3000) {
            p.seekTo(0)
            p.play()
            isTransitioning = false
            releaseWakeLock()
            return
        }

        if (track_position <= 0) {
            if (p.repeatMode == Player.REPEAT_MODE_ALL) {
                track_position = trackQueue!!.size - 1
            } else {
                p.seekTo(0)
                p.play()
                isTransitioning = false
                releaseWakeLock()
                return
            }
        } else {
            if (p.shuffleModeEnabled && trackQueue!!.size > 1) {
                track_position = (Math.random() * trackQueue!!.size).toInt()
            } else { track_position-- }
        }
        
        MUSIC_ID = trackQueue!![track_position]
        playTrack()
        showNotification()
        updateWidget()
    }

    private fun getStateString(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }
}
