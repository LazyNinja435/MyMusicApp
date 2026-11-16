package com.harsh.shah.saavnmp3.utils

import android.app.Application
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.harsh.shah.saavnmp3.records.SongResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SongRecommendationFilterTest {
    @Test
    fun keepsFirstRankedCopyAndFillsLimitWithUniqueTracks() {
        val results = uniqueSongRecommendations(
            candidates = listOf(
                song("first", "Chikiri Chikiri"),
                song("soundtrack", "Chikiri Chikiri (From &quot;Peddi&quot;)"),
                song("compilation", "CHIKIRI  CHIKIRI (From “Peddi”)"),
                song("first", "Different metadata for the same ID"),
                song("second", "Ala Bolelo")
            ),
            seed = song("seed", "Naalo Nenu"),
            queuedTracks = emptyMap(),
            limit = 2
        )

        assertEquals(listOf("first", "second"), results.map { it.id })
    }

    @Test
    fun excludesSeedAndEarlierQueueEntriesAcrossCatalogIds() {
        val results = uniqueSongRecommendations(
            candidates = listOf(
                song("seed-copy", "Naalo Nenu (From 'Shatamanam Bhavati')"),
                song("old-copy", "Chikiri Chikiri (From Peddi)"),
                song("known-id", "Updated title"),
                song("", "Missing ID"),
                song("live", "Chikiri Chikiri (Live)"),
                song("telugu-1", "తెలుగు పాట"),
                song("telugu-2", "మరో పాట")
            ),
            seed = song("seed", "Naalo Nenu"),
            queuedTracks = mapOf("old" to "Chikiri Chikiri", "known-id" to ""),
            limit = 10
        )

        assertEquals(listOf("live", "telugu-1", "telugu-2"), results.map { it.id })
    }

    private fun song(id: String, title: String): SongResponse.Song {
        val json = JsonObject().apply {
            addProperty("id", id)
            addProperty("name", title)
        }
        return Gson().fromJson(json, SongResponse.Song::class.java)
    }
}
