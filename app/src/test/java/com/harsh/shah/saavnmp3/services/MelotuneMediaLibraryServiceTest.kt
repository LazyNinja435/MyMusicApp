package com.harsh.shah.saavnmp3.services

import android.content.Intent
import androidx.media3.session.MediaLibraryService
import androidx.test.core.app.ApplicationProvider
import com.harsh.shah.saavnmp3.automotive.MediaLibraryBrowser
import com.harsh.shah.saavnmp3.automotive.MediaLibraryRepository
import com.harsh.shah.saavnmp3.importer.PlaylistImportRepository
import com.harsh.shah.saavnmp3.records.sharedpref.SavedLibraries
import com.harsh.shah.saavnmp3.utils.MusicPlayerManager
import com.harsh.shah.saavnmp3.utils.SharedPreferenceManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MelotuneMediaLibraryServiceTest {

    @Test
    fun `service binds as MediaLibraryService and exposes imported YouTube playlists`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Persist an imported YouTube playlist via PlaylistImportRepository
        val repo = PlaylistImportRepository(context)
        val saved = repo.saveImportedPlaylist(
            youtubePlaylistId = "PL_ROBO_12345",
            sourceUrl = "https://www.youtube.com/playlist?list=PL_ROBO_12345",
            playlistName = "Robolectric YT Playlist",
            coverImage = "https://example.com/cover.jpg",
            songs = listOf(
                SavedLibraries.Library.Songs("s101", "Song 101", "Artist 101", "https://example.com/101.jpg")
            )
        )
        assertTrue(SharedPreferenceManager.getInstance(context).isYouTubeImportedPlaylist(saved.id))

        // Verify MediaLibraryRepository + MediaLibraryBrowser expose it under Imported YouTube Playlists
        val autoRepo = MediaLibraryRepository(context)
        val browser = MediaLibraryBrowser(autoRepo)
        val ytPlaylists = browser.getChildren(MediaLibraryBrowser.NODE_YT_PLAYLISTS)
        assertTrue(ytPlaylists.any { it.mediaMetadata.title.toString() == "Robolectric YT Playlist" })

        val ytPlaylistNode = ytPlaylists.first { it.mediaMetadata.title.toString() == "Robolectric YT Playlist" }
        val tracks = browser.getChildren(ytPlaylistNode.mediaId)
        assertEquals(1, tracks.size)
        assertEquals("Song 101", tracks[0].mediaMetadata.title.toString())

        // Verify MelotuneMediaLibraryService creation, binding, and cleanup
        val controller = Robolectric.buildService(MelotuneMediaLibraryService::class.java)
        val service = controller.create().get()
        assertTrue(MelotuneMediaLibraryService.isRunning)
        assertNotNull(MusicPlayerManager.player)

        val binder = service.onBind(Intent(MediaLibraryService.SERVICE_INTERFACE))
        assertNotNull(binder)

        controller.destroy()
        assertFalse(MelotuneMediaLibraryService.isRunning)
    }
}
