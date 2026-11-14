package com.harsh.shah.saavnmp3.importer

import com.harsh.shah.saavnmp3.records.SongResponse.Album
import com.harsh.shah.saavnmp3.records.SongResponse.Artist
import com.harsh.shah.saavnmp3.records.SongResponse.Artists
import com.harsh.shah.saavnmp3.records.SongResponse.Song
import com.harsh.shah.saavnmp3.records.sharedpref.SavedLibraries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistImportCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun song(id: String, name: String, artist: String, album: String): Song {
        return Song(
            id = id,
            name = name,
            type = "song",
            year = "2023",
            releaseDate = null,
            duration = 200.0,
            label = null,
            explicitContent = false,
            playCount = 500,
            language = "hindi",
            hasLyrics = false,
            lyricsId = null,
            lyrics = null,
            url = null,
            copyright = null,
            album = Album("alb_$id", album, null),
            artists = Artists(
                primary = mutableListOf(Artist("art_$id", artist, "singer", "artist", null, null)),
                featured = mutableListOf(),
                all = mutableListOf()
            ),
            image = mutableListOf(),
            downloadUrl = mutableListOf()
        )
    }

    private class FakePersistence : PlaylistPersistence {
        var savedLibrary: SavedLibraries.Library? = null
        var checkpoint: PlaylistImportCheckpoint? = null
        var apiKey: String = "test_api_key"

        override fun saveImportedPlaylist(
            youtubePlaylistId: String,
            sourceUrl: String,
            playlistName: String,
            coverImage: String?,
            songs: List<SavedLibraries.Library.Songs>
        ): SavedLibraries.Library {
            val lib = SavedLibraries.Library(
                id = "yt_import_$youtubePlaylistId",
                isCreatedByUser = true,
                isAlbum = false,
                name = playlistName,
                image = coverImage,
                description = "${songs.size} songs",
                songs = songs.toMutableList()
            )
            savedLibrary = lib
            checkpoint = null
            return lib
        }

        override fun saveCheckpoint(checkpoint: PlaylistImportCheckpoint?) {
            this.checkpoint = checkpoint
        }

        override fun loadCheckpoint(): PlaylistImportCheckpoint? = checkpoint
        override fun getYouTubeApiKey(): String = apiKey
        override fun setYouTubeApiKey(apiKey: String) {
            this.apiKey = apiKey
        }
    }

    @Test
    fun `imports playlist deduplicates and saves matched songs`() = runTest(testDispatcher) {
        val ytSource = object : YouTubePlaylistDataSource {
            override fun fetchPlaylist(
                playlistId: String,
                apiKey: String,
                onPageFetched: ((Int, Int?) -> Unit)?
            ): YouTubePlaylistFetchResult {
                return YouTubePlaylistFetchResult(
                    metadata = YouTubePlaylistMetadata(playlistId, "My YT Mix", "", null, 4),
                    tracks = listOf(
                        YouTubePlaylistTrack("v1", 0, "Chaleya (Official Video) | Jawan | Arijit Singh", "T-Series", "Arijit Singh - Topic", 200, null, true),
                        YouTubePlaylistTrack("v1", 1, "Chaleya (Official Video) | Jawan | Arijit Singh", "T-Series", "Arijit Singh - Topic", 200, null, true),
                        YouTubePlaylistTrack("v2", 2, "Deleted video", null, null, null, null, false, "Deleted video"),
                        YouTubePlaylistTrack("v3", 3, "Completely Unknown Track XYZ123", null, null, null, null, true)
                    )
                )
            }
        }

        val saavnSearch = object : JioSaavnSearchDataSource {
            override suspend fun searchSongs(query: String, limit: Int): List<Song> {
                return if (query.contains("Chaleya", ignoreCase = true)) {
                    listOf(song("saavn_chaleya", "Chaleya", "Arijit Singh", "Jawan"))
                } else {
                    emptyList()
                }
            }
        }

        val persistence = FakePersistence()
        val coordinator = PlaylistImportCoordinator(
            youTubeClient = ytSource,
            jioSaavnSearch = saavnSearch,
            matcher = JioSaavnSongMatcher(saavnSearch),
            persistence = persistence,
            ioDispatcher = testDispatcher
        )

        coordinator.startImport("https://www.youtube.com/playlist?list=PL1234567890ABCDEF")
        advanceUntilIdle()

        val state = coordinator.uiState.value
        assertEquals(ImportPhase.COMPLETED, state.phase)
        assertEquals(1, state.matchedCount)
        assertEquals(1, state.duplicateCount)
        assertEquals(1, state.unavailableCount)
        assertEquals(1, state.failedCount)

        // Manually select candidate for the failed item & save
        coordinator.selectCandidate(3, song("saavn_manual", "Manual Track", "Artist", "Album"))
        val saved = coordinator.saveImportedPlaylist("Road Trip Mix")
        assertNotNull(saved)
        assertEquals("Road Trip Mix", saved?.name)
        assertEquals(2, saved?.songs?.size)
        assertEquals("saavn_chaleya", saved?.songs?.get(0)?.id)
        assertEquals("saavn_manual", saved?.songs?.get(1)?.id)
    }

    @Test
    fun `cancellation sets CANCELLED phase and preserves checkpoint`() = runTest(testDispatcher) {
        val ytSource = object : YouTubePlaylistDataSource {
            override fun fetchPlaylist(
                playlistId: String,
                apiKey: String,
                onPageFetched: ((Int, Int?) -> Unit)?
            ): YouTubePlaylistFetchResult {
                val tracks = (1..10).map { i ->
                    YouTubePlaylistTrack("v$i", i - 1, "Song $i", "Channel", null, 200, null, true)
                }
                return YouTubePlaylistFetchResult(
                    metadata = YouTubePlaylistMetadata(playlistId, "Cancel Test", "", null, 10),
                    tracks = tracks
                )
            }
        }
        val saavnSearch = object : JioSaavnSearchDataSource {
            override suspend fun searchSongs(query: String, limit: Int): List<Song> {
                kotlinx.coroutines.delay(100)
                return listOf(song("id_1", "Song 1", "Channel", "Album"))
            }
        }
        val persistence = FakePersistence()
        val coordinator = PlaylistImportCoordinator(
            youTubeClient = ytSource,
            jioSaavnSearch = saavnSearch,
            matcher = JioSaavnSongMatcher(saavnSearch),
            persistence = persistence,
            ioDispatcher = testDispatcher
        )

        coordinator.startImport("https://www.youtube.com/playlist?list=PL1234567890ABCDEF")
        testScheduler.runCurrent()
        coordinator.cancelImport()
        advanceUntilIdle()

        assertEquals(ImportPhase.CANCELLED, coordinator.uiState.value.phase)
        assertNotNull(persistence.loadCheckpoint())
    }

    @Test
    fun `needs review automatically selects first match and includes it when saving`() = runTest(testDispatcher) {
        val ytSource = object : YouTubePlaylistDataSource {
            override fun fetchPlaylist(
                playlistId: String,
                apiKey: String,
                onPageFetched: ((Int, Int?) -> Unit)?
            ): YouTubePlaylistFetchResult {
                return YouTubePlaylistFetchResult(
                    metadata = YouTubePlaylistMetadata(playlistId, "Auto Select Mix", "", null, 1),
                    tracks = listOf(
                        YouTubePlaylistTrack("v1", 0, "Mastaaru Mastaaru Full Video", "Channel", null, 250, null, true)
                    )
                )
            }
        }
        val saavnSearch = object : JioSaavnSearchDataSource {
            override suspend fun searchSongs(query: String, limit: Int): List<Song> {
                return listOf(
                    song("id_first", "Mastaaru Mastaaru", "G.V. Prakash", "Sir"),
                    song("id_second", "Mastaaru Mastaaru Lofi", "Other", "Sir")
                )
            }
        }
        val persistence = FakePersistence()
        val coordinator = PlaylistImportCoordinator(
            youTubeClient = ytSource,
            jioSaavnSearch = saavnSearch,
            matcher = JioSaavnSongMatcher(saavnSearch),
            persistence = persistence,
            ioDispatcher = testDispatcher
        )

        coordinator.startImport("https://www.youtube.com/playlist?list=PL1234567890ABCDEF")
        advanceUntilIdle()

        val state = coordinator.uiState.value
        assertEquals(ImportPhase.COMPLETED, state.phase)
        val item = state.items.first()
        assertNotNull(item.selectedSong)
        assertEquals("id_first", item.selectedSong?.id)

        val saved = coordinator.saveImportedPlaylist()
        assertNotNull(saved)
        assertEquals(1, saved!!.songs?.size)
        assertEquals("id_first", saved.songs?.first()?.id)
    }
}
