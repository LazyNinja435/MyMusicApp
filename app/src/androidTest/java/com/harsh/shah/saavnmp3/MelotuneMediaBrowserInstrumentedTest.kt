package com.harsh.shah.saavnmp3

import android.content.ComponentName
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.harsh.shah.saavnmp3.automotive.MediaLibraryBrowser
import com.harsh.shah.saavnmp3.services.MelotuneMediaLibraryService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
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
}
