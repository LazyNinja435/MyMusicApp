package com.harsh.shah.saavnmp3

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.media.browse.MediaBrowser as LegacyMediaBrowser
import android.media.session.MediaController
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.harsh.shah.saavnmp3.automotive.MediaLibraryBrowser
import com.harsh.shah.saavnmp3.services.MelotuneMediaLibraryService
import com.harsh.shah.saavnmp3.utils.MusicPlayerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class MelotuneMediaBrowserInstrumentedTest {

    @Test
    fun mediaBrowserConnectsAndBrowsesMelotuneHierarchy() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val token = SessionToken(context, ComponentName(context, MelotuneMediaLibraryService::class.java))
        val browserFuture = MediaBrowser.Builder(context, token).buildAsync()
        val browser = browserFuture.get(15, TimeUnit.SECONDS)

        try {
            var rootMediaId: String? = null
            var rootTitle: String? = null
            var topTitles: List<String> = emptyList()
            var playlistTitles: List<String> = emptyList()

            val rootFuture = instrumentation.let {
                var f: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<androidx.media3.common.MediaItem>>? = null
                it.runOnMainSync { f = browser.getLibraryRoot(null) }
                f!!
            }
            val rootResult = rootFuture.get(10, TimeUnit.SECONDS)
            rootMediaId = rootResult.value?.mediaId
            rootTitle = rootResult.value?.mediaMetadata?.title?.toString()

            var topFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>? = null
            instrumentation.runOnMainSync {
                topFuture = browser.getChildren(MediaLibraryBrowser.ROOT_ID, 0, 20, null)
            }
            topTitles = topFuture!!.get(10, TimeUnit.SECONDS).value?.map { it.mediaMetadata.title.toString() } ?: emptyList()

            var playlistFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>? = null
            instrumentation.runOnMainSync {
                playlistFuture = browser.getChildren(MediaLibraryBrowser.TAB_PLAYLISTS, 0, 20, null)
            }
            playlistTitles = playlistFuture!!.get(10, TimeUnit.SECONDS).value?.map { it.mediaMetadata.title.toString() } ?: emptyList()

            assertEquals(MediaLibraryBrowser.ROOT_ID, rootMediaId)
            assertEquals("Melotune", rootTitle)
            assertEquals(listOf("Home", "Playlists", "Albums", "Artists", "Downloads"), topTitles)
            assertTrue(playlistTitles.contains("Imported YouTube Playlists"))
        } finally {
            instrumentation.runOnMainSync {
                MediaBrowser.releaseFuture(browserFuture)
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun legacyBrowserPlaysDownloadAndAdvancesAtEndOfTrack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val first = insertSilentMp3(context, "Melotune test first")
        var second: Uri? = null
        var browser: LegacyMediaBrowser? = null
        try {
            second = insertSilentMp3(context, "Melotune test second")
            val connected = CompletableFuture<LegacyMediaBrowser>()
            instrumentation.runOnMainSync {
                val component = ComponentName(context, MelotuneMediaLibraryService::class.java)
                browser = LegacyMediaBrowser(context, component, object : LegacyMediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        connected.complete(browser!!)
                    }

                    override fun onConnectionFailed() {
                        connected.completeExceptionally(AssertionError("Legacy browser connection failed"))
                    }
                }, null)
                browser?.connect()
            }

            val legacy = connected.get(10, TimeUnit.SECONDS)
            val roots = loadLegacyChildren(legacy, MediaLibraryBrowser.ROOT_ID)
            assertTrue(roots.any { it.mediaId == MediaLibraryBrowser.TAB_DOWNLOADS })
            val downloads = loadLegacyChildren(legacy, MediaLibraryBrowser.TAB_DOWNLOADS)
            val selected = downloads.first { it.description.title.toString().startsWith("Melotune test") }
            val next = downloads.dropWhile { it != selected }.drop(1)
                .first { it.description.title.toString().startsWith("Melotune test") }

            instrumentation.runOnMainSync {
                MediaController(context, legacy.sessionToken).transportControls
                    .playFromMediaId(selected.mediaId, null)
            }
            waitForTrack(selected.description.title.toString())
            waitForTrack(next.description.title.toString())

            instrumentation.runOnMainSync { MusicPlayerManager.stopPlayback() }
            instrumentation.runOnMainSync {
                MusicPlayerManager.player?.repeatMode = Player.REPEAT_MODE_ALL
                MediaController(context, legacy.sessionToken).transportControls
                    .playFromMediaId(selected.mediaId, null)
            }
            waitForTrack(selected.description.title.toString())
            waitForTrack(next.description.title.toString())
            waitForTrack(selected.description.title.toString())
        } finally {
            instrumentation.runOnMainSync {
                MusicPlayerManager.stopPlayback()
                MusicPlayerManager.player?.repeatMode = Player.REPEAT_MODE_OFF
                browser?.disconnect()
            }
            context.contentResolver.delete(first, null, null)
            second?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun loadLegacyChildren(browser: LegacyMediaBrowser, parent: String): List<LegacyMediaBrowser.MediaItem> {
        val result = CompletableFuture<List<LegacyMediaBrowser.MediaItem>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            browser.subscribe(parent, object : LegacyMediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: List<LegacyMediaBrowser.MediaItem>) {
                    result.complete(children)
                }

                override fun onError(parentId: String) {
                    result.completeExceptionally(AssertionError("Could not browse $parentId"))
                }
            })
        }
        return result.get(10, TimeUnit.SECONDS)
    }

    private fun waitForTrack(title: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < 10000L) {
            var playing = false
            instrumentation.runOnMainSync {
                val player = MusicPlayerManager.player
                playing = player?.playbackState == Player.STATE_READY && player.isPlaying &&
                    player.currentMediaItem?.mediaMetadata?.title.toString() == title
            }
            if (playing) return
            SystemClock.sleep(25L)
        }
        throw AssertionError("Never reached playing state for $title")
    }

    private fun insertSilentMp3(context: Context, title: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$title.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Melotune")
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
        // A silent MPEG-1 Layer III frame at 128 kbps and 44.1 kHz.
        val silentFrame = ByteArray(417)
        byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x00).copyInto(silentFrame)
        requireNotNull(resolver.openOutputStream(uri)).use { output ->
            repeat(80) { output.write(silentFrame) }
        }
        return uri
    }
}
