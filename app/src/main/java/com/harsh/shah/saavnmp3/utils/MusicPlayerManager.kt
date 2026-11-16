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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.ExoDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
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
import com.harsh.shah.saavnmp3.automotive.AutoTrackItem
import com.harsh.shah.saavnmp3.network.ApiManager
import com.harsh.shah.saavnmp3.network.utility.RequestNetwork
import com.harsh.shah.saavnmp3.records.SongResponse
import com.harsh.shah.saavnmp3.services.MelotuneMediaLibraryService
import com.harsh.shah.saavnmp3.widgets.WidgetPlayerProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
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
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var trackRequest: Job? = null
    private var playbackGeneration = 0L

    private data class ResolvedTrack(
        val response: SongResponse?,
        val description: String,
        val queueIds: List<String>?,
        val queueIndex: Int
    )

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
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, mediaDataSourceFactory))
            )
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
                    Player.STATE_ENDED -> scheduleAutomaticAdvance()
                    else -> {
                        updateWidget()
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    // ExoPlayer has one item. Repeat-all must advance the app queue.
                    if (player?.repeatMode == Player.REPEAT_MODE_ALL &&
                        trackQueue.orEmpty().count { !it.isNullOrBlank() } > 1
                    ) {
                        scheduleAutomaticAdvance(repeated = true)
                    }
                    return
                }
                val track = mediaItem?.localConfiguration?.tag as? ResolvedTrack ?: return
                applyResolvedTrack(mediaItem, track)
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

    private fun scheduleAutomaticAdvance(repeated: Boolean = false) {
        val generation = playbackGeneration
        mainHandler.post {
            val p = player ?: return@post
            val atTrackBoundary = if (repeated) {
                p.repeatMode == Player.REPEAT_MODE_ALL
            } else {
                p.playbackState == Player.STATE_ENDED
            }
            if (generation == playbackGeneration && trackRequest?.isActive != true &&
                atTrackBoundary && p.playWhenReady
            ) {
                advanceTrack(automatic = true)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            ctx.startService(Intent(ctx, MelotuneMediaLibraryService::class.java))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Unable to start media service from the background", e)
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
                if (!SONG_URL.isNullOrBlank()) prepareMediaPlayer() else playTrack()
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
        val url = SONG_URL?.takeIf { it.isNotBlank() } ?: return
        trackRequest?.cancel()
        cancelAutoplay()
        val mediaUri = if (url.startsWith("/")) Uri.fromFile(File(url)) else Uri.parse(url)

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
            .setMediaId(MUSIC_ID ?: url)
            .setMediaMetadata(metadata)
            .build()

        isTrackDownloaded = mediaUri.scheme == "file"
        currentlyPlayingTrackId = MUSIC_ID
        startMediaItem(p, mediaItem)
    }

    private fun startMediaItem(player: Player, mediaItem: MediaItem) {
        playbackGeneration++
        acquireWakeLock(15000L)
        ensureMediaServiceStarted()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        showNotification()
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

    internal suspend fun resolveMediaItem(
        trackId: String,
        queueIds: List<String>? = null,
        queueIndex: Int = 0,
        download: AutoTrackItem? = null
    ): MediaItem? {
        val context = appContext ?: return null
        val isFile = trackId.startsWith("/") || trackId.startsWith("file://")
        val response = if (download == null && !isFile) {
            val cached = sharedPreferenceManager?.getSongResponseById(trackId)
            cached?.takeIf { playableSong(it) != null } ?: fetchSong(context, trackId)
        } else {
            null
        }
        val song = playableSong(response)
        val offlineTrack = download ?: if (song == null) findDownloadedTrack(context, trackId) else null
        val file = offlineTrack?.localFilePath?.let(::File)?.takeIf { it.isFile }
        val uri = when {
            file != null -> Uri.fromFile(file)
            song != null -> Uri.parse(getDownloadUrl(song.downloadUrl))
            else -> return null
        }
        val title = song?.name() ?: offlineTrack?.title
        val artists = song?.artists?.primary?.mapNotNull { it?.name() }?.joinToString(", ")
            ?.takeIf { it.isNotBlank() } ?: offlineTrack?.artist
        val artwork = song?.image?.lastOrNull()?.url ?: offlineTrack?.artworkUrl
        val description = if (song != null) {
            "${MusicOverviewActivity.convertPlayCount(song.playCount ?: 0)} plays | ${song.year} | ${song.copyright}"
        } else {
            artists.orEmpty()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artists)
            .setAlbumTitle(song?.album?.name() ?: offlineTrack?.album)
            .setArtworkUri(artwork?.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        return MediaItem.Builder()
            .setMediaId(trackId)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .setTag(ResolvedTrack(response?.takeIf { song != null }, description, queueIds, queueIndex))
            .build()
    }

    private fun playableSong(response: SongResponse?): SongResponse.Song? {
        if (response?.success != true) return null
        return response.data?.firstOrNull()?.takeIf {
            Uri.parse(getDownloadUrl(it.downloadUrl)).scheme in listOf("http", "https")
        }
    }

    private suspend fun fetchSong(context: Context, trackId: String): SongResponse? =
        suspendCancellableCoroutine { continuation ->
            ApiManager(context).retrieveSongById(trackId, null, object : RequestNetwork.RequestListener {
                override fun onResponse(tag: String?, response: String?, responseHeaders: HashMap<String?, Any?>?) {
                    if (!continuation.isActive) return
                    val song = runCatching { Gson().fromJson(response, SongResponse::class.java) }
                    continuation.resume(song.getOrNull())
                }

                override fun onErrorResponse(tag: String?, message: String?) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }

    private suspend fun findDownloadedTrack(context: Context, trackId: String): AutoTrackItem? =
        withContext(Dispatchers.IO) {
            val path = if (trackId.startsWith("file://")) Uri.parse(trackId).path else trackId
            val match = TrackDownloader.getDownloadedTracks(context, includeArtwork = false)
                .filterNotNull().firstOrNull { it.trackUID == trackId || it.file?.absolutePath == path }
            val file = match?.file ?: return@withContext null
            AutoTrackItem(trackId, match.title ?: file.nameWithoutExtension, match.artist.orEmpty(),
                match.album, localFilePath = file.absolutePath)
        }

    private fun applyResolvedTrack(item: MediaItem, track: ResolvedTrack) {
        playbackGeneration++
        if (track.queueIds != null) {
            trackRequest?.cancel()
            cancelAutoplay()
            trackQueue = ArrayList(track.queueIds)
            track_position = track.queueIndex
        }

        CURRENT_TRACK = track.response
        SONG_URL = item.localConfiguration?.uri?.toString()
        isTrackDownloaded = item.localConfiguration?.uri?.scheme == "file"
        currentlyPlayingTrackId = item.mediaId
        setMusicDetails(item.mediaMetadata.artworkUri?.toString().orEmpty(),
            item.mediaMetadata.title?.toString().orEmpty(), track.description, item.mediaId)
        notifyPlaybackListeners()

        val song = track.response?.data?.firstOrNull() ?: return
        val artist = song.artists?.primary?.firstOrNull()
        val artists = song.artists?.all?.mapNotNull { it?.name() }?.joinToString(", ").orEmpty()
        sharedPreferenceManager?.addRecentlyPlayed(
            com.harsh.shah.saavnmp3.records.sharedpref.SavedLibraries.Library.Songs(
                song.id, song.name(), artists, IMAGE_URL
            ),
            artistId = artist?.id,
            artistName = artist?.name()
        )
    }

    fun playTrack() {
        val targetId = MUSIC_ID?.takeIf { it.isNotBlank() } ?: return
        val p = player ?: return
        cancelAutoplay()
        trackRequest?.cancel()
        playbackGeneration++
        isTransitioning = true
        acquireWakeLock()
        trackRequest = playbackScope.launch {
            try {
                val item = resolveMediaItem(targetId)
                if (item != null) {
                    startMediaItem(p, item)
                } else {
                    Log.w(TAG, "No playable source for $targetId")
                    MUSIC_ID = currentlyPlayingTrackId
                    track_position = trackQueue?.indexOf(MUSIC_ID) ?: -1
                    showNotification()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not play $targetId", e)
            } finally {
                isTransitioning = false
                releaseWakeLock()
            }
        }
    }

    private var isFetchingAutoplay = false

    private fun fetchAndAppendAutoplayRecommendations(currentSong: SongResponse.Song) {
        if (isFetchingAutoplay) return
        val ctx = appContext ?: return
        val playingId = currentlyPlayingTrackId ?: return
        isFetchingAutoplay = true
        val requestId = ++autoplayRequestId
        val currentPosition = track_position

        RecommendationManager.getRecommendationsForSong(ctx, currentSong, limit = 10) { recommendations ->
            if (requestId != autoplayRequestId) return@getRecommendationsForSong
            isFetchingAutoplay = false
            val p = player ?: return@getRecommendationsForSong
            if (playingId != currentlyPlayingTrackId || currentPosition != track_position ||
                p.playbackState != Player.STATE_ENDED || !p.playWhenReady
            ) return@getRecommendationsForSong

            val queue = trackQueue ?: arrayListOf<String?>(playingId)
            val newIds = recommendations.mapNotNull { it.id?.takeIf(String::isNotBlank) }
                .distinct().filter { it !in queue && it != currentSong.id }
            if (newIds.isNotEmpty()) {
                trackQueue = queue
                val next = queue.size
                queue.addAll(newIds)
                track_position = next
                MUSIC_ID = queue[next]
                playTrack()
            }
        }
    }

    fun stopPlayback() {
        playbackGeneration++
        trackRequest?.cancel()
        cancelAutoplay()
        player?.pause()
        player?.stop()
    }

    fun setPlaybackSpeed(speed: Float) {
        player?.playbackParameters = androidx.media3.common.PlaybackParameters(speed)
    }

    fun getPlaybackSpeed(): Float {
        return player?.playbackParameters?.speed ?: 1.0f
    }

    fun nextTrack() = advanceTrack(automatic = false)

    private fun advanceTrack(automatic: Boolean) {
        val p = player ?: return
        val queue = trackQueue.orEmpty()
        val currentId = if (automatic) currentlyPlayingTrackId else MUSIC_ID
        val index = if (queue.getOrNull(track_position) == currentId) track_position else queue.indexOf(currentId)
        val validIndices = queue.indices.filter { !queue[it].isNullOrBlank() }
        val next = when {
            p.shuffleModeEnabled && validIndices.any { it != index } ->
                validIndices.filter { it != index }.random()
            else -> validIndices.firstOrNull { it > index }
                ?: validIndices.firstOrNull().takeIf { p.repeatMode == Player.REPEAT_MODE_ALL }
        }

        if (next != null) {
            track_position = next
            MUSIC_ID = queue[next]
            playTrack()
        } else if (automatic) {
            val song = CURRENT_TRACK?.data?.firstOrNull()
            if (sharedPreferenceManager?.isAutoplayEnabled == true && song != null) {
                fetchAndAppendAutoplayRecommendations(song)
            }
        } else if (p.currentMediaItem != null) {
            p.seekTo(0L)
            p.play()
        }
    }

    fun prevTrack() {
        val p = player ?: return
        val queue = trackQueue.orEmpty()
        val index = track_position.takeIf { queue.getOrNull(it) == MUSIC_ID } ?: queue.indexOf(MUSIC_ID)
        val previous = queue.indices.lastOrNull { it < index && !queue[it].isNullOrBlank() }
            ?: queue.indices.lastOrNull { !queue[it].isNullOrBlank() }
                .takeIf { p.repeatMode == Player.REPEAT_MODE_ALL }
        if (p.currentPosition <= 3000L && previous != null) {
            track_position = previous
            MUSIC_ID = queue[previous]
            playTrack()
        } else if (p.currentMediaItem != null) {
            p.seekTo(0L)
            p.play()
        }
    }

    private fun getStateString(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }
}
