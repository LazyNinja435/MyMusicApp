package com.harsh.shah.saavnmp3.automotive

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaLibraryBrowserTest {

    private class FakeDataSource : MediaLibraryDataSource {
        val recent = listOf(AutoTrackItem("r1", "Recent Song", "Artist A", "Album A"))
        val favorites = listOf(AutoTrackItem("f1", "Liked Song", "Artist B", "Album B"))
        val recommended = listOf(AutoTrackItem("rec1", "Rec Song", "Artist C", "Album C"))
        val userPlaylists = listOf(
            AutoCollectionItem("up1", "Gym Mix", "2 songs", songs = recent)
        )
        val ytPlaylists = listOf(
            AutoCollectionItem("yt_import_1", "Telugu Hits YT", "1 song", isYouTubeImport = true, songs = favorites)
        )
        val albums = listOf(
            AutoCollectionItem("alb1", "RRR", "MM Keeravaani", isAlbum = true, songs = recent)
        )
        val artists = listOf(
            AutoCollectionItem("art1", "Arijit Singh", "Singer")
        )
        val downloads = listOf(
            AutoTrackItem("d1", "Offline Track", "Offline Artist", localFilePath = "/tmp/offline.m4a")
        )

        override suspend fun getRecentlyPlayed() = recent
        override suspend fun getFavorites() = favorites
        override suspend fun getRecommendedSongs() = recommended
        override suspend fun getUserPlaylists() = userPlaylists
        override suspend fun getImportedYouTubePlaylists() = ytPlaylists
        override suspend fun getAlbums() = albums
        override suspend fun getArtists() = artists
        override suspend fun getDownloadedTracks() = downloads
        override suspend fun getPlaylistTracks(playlistId: String) =
            (userPlaylists + ytPlaylists).firstOrNull { it.id == playlistId }?.songs ?: emptyList()
        override suspend fun getAlbumTracks(albumId: String) =
            albums.firstOrNull { it.id == albumId }?.songs ?: emptyList()
        override suspend fun getArtistTracks(artistId: String) = recent
        override suspend fun searchTracks(query: String) =
            (recent + favorites + recommended).filter { it.title.contains(query, ignoreCase = true) }
    }

    @Test
    fun `exposes required Melotune hierarchy and imported YouTube playlists`() = runTest {
        val browser = MediaLibraryBrowser(FakeDataSource())

        val root = browser.getRootItem()
        assertEquals(MediaLibraryBrowser.ROOT_ID, root.mediaId)
        assertTrue(root.mediaMetadata.isBrowsable == true)
        assertFalse(root.mediaMetadata.isPlayable == true)

        val topTabs = browser.getChildren(MediaLibraryBrowser.ROOT_ID).map { it.mediaMetadata.title.toString() }
        assertEquals(listOf("Home", "Playlists", "Albums", "Artists", "Downloads"), topTabs)

        val homeChildren = browser.getChildren(MediaLibraryBrowser.TAB_HOME).map { it.mediaMetadata.title.toString() }
        assertEquals(listOf("Recently Played", "Favorites", "Recommended Songs"), homeChildren)

        val playlistFolders = browser.getChildren(MediaLibraryBrowser.TAB_PLAYLISTS).map { it.mediaMetadata.title.toString() }
        assertEquals(listOf("User-Created Playlists", "Imported YouTube Playlists"), playlistFolders)

        val ytPlaylists = browser.getChildren(MediaLibraryBrowser.NODE_YT_PLAYLISTS)
        assertEquals(1, ytPlaylists.size)
        assertEquals("Telugu Hits YT", ytPlaylists[0].mediaMetadata.title.toString())

        val ytTracks = browser.getChildren(ytPlaylists[0].mediaId)
        assertEquals(1, ytTracks.size)
        assertTrue(ytTracks[0].mediaMetadata.isPlayable == true)
        assertEquals("Liked Song", ytTracks[0].mediaMetadata.title.toString())

        val (queue, startIdx) = browser.resolveQueueForMediaItem(ytTracks[0])
        assertEquals(1, queue.size)
        assertEquals(0, startIdx)
        assertEquals("f1", queue[0].id)
    }

    @Test
    fun `search returns matching playable tracks`() = runTest {
        val browser = MediaLibraryBrowser(FakeDataSource())
        val results = browser.search("Liked")
        assertEquals(1, results.size)
        assertEquals("Liked Song", results[0].mediaMetadata.title.toString())
        assertTrue(results[0].mediaMetadata.isPlayable == true)
    }
}
