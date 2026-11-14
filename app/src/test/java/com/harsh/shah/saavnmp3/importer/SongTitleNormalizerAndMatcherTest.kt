package com.harsh.shah.saavnmp3.importer

import com.harsh.shah.saavnmp3.records.SongResponse.Album
import com.harsh.shah.saavnmp3.records.SongResponse.Artist
import com.harsh.shah.saavnmp3.records.SongResponse.Artists
import com.harsh.shah.saavnmp3.records.SongResponse.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongTitleNormalizerAndMatcherTest {

    private fun fakeSong(
        id: String,
        name: String,
        artist: String,
        album: String,
        durationSec: Double = 215.0,
        language: String = "telugu"
    ): Song {
        return Song(
            id = id,
            name = name,
            type = "song",
            year = "2022",
            releaseDate = null,
            duration = durationSec,
            label = null,
            explicitContent = false,
            playCount = 1000000,
            language = language,
            hasLyrics = false,
            lyricsId = null,
            lyrics = null,
            url = null,
            copyright = null,
            album = Album("alb_$id", album, null),
            artists = Artists(
                primary = mutableListOf(Artist("art_$id", artist, "singer", "artist", null, null)),
                featured = mutableListOf(),
                all = mutableListOf(Artist("art_$id", artist, "singer", "artist", null, null))
            ),
            image = mutableListOf(),
            downloadUrl = mutableListOf()
        )
    }

    @Test
    fun `normalizes telugu and hindi film song titles cleanly`() {
        val telugu = SongTitleNormalizer.normalize(
            "Naatu Naatu (Full Video Song) | RRR | NTR, Ram Charan | M.M. Keeravaani",
            "Lahari Music"
        )
        assertEquals("Naatu Naatu", telugu.cleanTitle)
        assertEquals("RRR", telugu.extractedAlbumOrMovie)

        val hindi = SongTitleNormalizer.normalize(
            "Kesariya - Brahmāstra | Ranbir Kapoor | Pritam | Arijit Singh [Official 4K Video]",
            "Sony Music India"
        )
        assertEquals("Kesariya", hindi.cleanTitle)
        assertEquals("Brahmastra", SongTitleNormalizer.normalizeForComparison(hindi.extractedAlbumOrMovie ?: "").replaceFirstChar { it.uppercase() })
    }

    @Test
    fun `auto matches high confidence telugu song`() = runTest {
        val search = object : JioSaavnSearchDataSource {
            override suspend fun searchSongs(query: String, limit: Int): List<Song> {
                return listOf(
                    fakeSong("s1", "Naatu Naatu", "Rahul Sipligunj", "RRR", 215.0, "telugu")
                )
            }
        }
        val matcher = JioSaavnSongMatcher(search)
        val ytTrack = YouTubePlaylistTrack(
            videoId = "v1",
            position = 0,
            title = "Naatu Naatu (Full Video Song) | RRR | Rahul Sipligunj",
            channelTitle = "Lahari Music",
            videoOwnerChannelTitle = "Rahul Sipligunj - Topic",
            durationSeconds = 215,
            thumbnailUrl = null,
            isAvailable = true
        )

        val result = matcher.match(ytTrack)
        assertEquals(MatchConfidenceStatus.MATCHED, result.status)
        assertEquals("s1", result.bestCandidate?.song?.id)
    }

    @Test
    fun `rejects remix version when original is requested`() = runTest {
        val search = object : JioSaavnSearchDataSource {
            override suspend fun searchSongs(query: String, limit: Int): List<Song> {
                return listOf(
                    fakeSong("s_remix", "Kesariya (DJ Remix)", "Arijit Singh", "Brahmastra", 260.0, "hindi")
                )
            }
        }
        val matcher = JioSaavnSongMatcher(search)
        val ytTrack = YouTubePlaylistTrack(
            videoId = "v2",
            position = 0,
            title = "Kesariya (Official Audio) | Arijit Singh",
            channelTitle = "Arijit Singh - Topic",
            videoOwnerChannelTitle = "Arijit Singh - Topic",
            durationSeconds = 268,
            thumbnailUrl = null,
            isAvailable = true
        )

        val result = matcher.match(ytTrack)
        assertTrue(result.status != MatchConfidenceStatus.MATCHED)
    }

    @Test
    fun `penalizes wrong language dubbed version`() = runTest {
        val search = object : JioSaavnSearchDataSource {
            override suspend fun searchSongs(query: String, limit: Int): List<Song> {
                return listOf(
                    fakeSong("s_hindi", "Naatu Naatu", "Vishal Mishra", "RRR", 215.0, "hindi"),
                    fakeSong("s_telugu", "Naatu Naatu", "Rahul Sipligunj", "RRR", 215.0, "telugu")
                )
            }
        }
        val matcher = JioSaavnSongMatcher(search)
        val ytTrack = YouTubePlaylistTrack(
            videoId = "v3",
            position = 0,
            title = "Naatu Naatu (Telugu) Full Song | RRR",
            channelTitle = "Lahari Music",
            videoOwnerChannelTitle = "Rahul Sipligunj - Topic",
            durationSeconds = 215,
            thumbnailUrl = null,
            isAvailable = true
        )

        val result = matcher.match(ytTrack)
        assertEquals(MatchConfidenceStatus.MATCHED, result.status)
        assertEquals("s_telugu", result.bestCandidate?.song?.id)
    }
}
