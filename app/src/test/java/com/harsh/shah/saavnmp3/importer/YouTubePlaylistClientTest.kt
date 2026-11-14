package com.harsh.shah.saavnmp3.importer

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YouTubePlaylistClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: YouTubePlaylistClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = YouTubePlaylistClient(baseUrl = server.url("").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetches paginated playlist and handles deleted and private videos`() {
        // 1. /playlists metadata
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "items": [{
                    "id": "PL_TEST_12345",
                    "snippet": { "title": "Telugu & Hindi Hits", "description": "Test desc" },
                    "contentDetails": { "itemCount": 3 },
                    "status": { "privacyStatus": "public" }
                  }]
                }
                """.trimIndent()
            )
        )
        // 2. /playlistItems page 1
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "nextPageToken": "PAGE_2",
                  "items": [
                    {
                      "snippet": {
                        "title": "Naatu Naatu (Full Video Song) | RRR",
                        "position": 0,
                        "videoOwnerChannelTitle": "Lahari Music",
                        "resourceId": { "videoId": "vid1" }
                      },
                      "contentDetails": { "videoId": "vid1" },
                      "status": { "privacyStatus": "public" }
                    },
                    {
                      "snippet": {
                        "title": "Deleted video",
                        "position": 1,
                        "resourceId": { "videoId": "vid2" }
                      },
                      "contentDetails": { "videoId": "vid2" },
                      "status": { "privacyStatus": "privacyStatusUnspecified" }
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        // 3. /playlistItems page 2
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "items": [
                    {
                      "snippet": {
                        "title": "Private video",
                        "position": 2,
                        "resourceId": { "videoId": "vid3" }
                      },
                      "contentDetails": { "videoId": "vid3" },
                      "status": { "privacyStatus": "private" }
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        // 4. /videos duration enrichment for available video vid1
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "items": [{
                    "id": "vid1",
                    "snippet": { "channelTitle": "Lahari Music" },
                    "contentDetails": { "duration": "PT3M35S" }
                  }]
                }
                """.trimIndent()
            )
        )

        val result = client.fetchPlaylist("PL_TEST_12345", "fake_key")
        assertEquals("Telugu & Hindi Hits", result.metadata.title)
        assertEquals(3, result.tracks.size)
        assertTrue(result.tracks[0].isAvailable)
        assertEquals(215, result.tracks[0].durationSeconds)
        assertFalse(result.tracks[1].isAvailable)
        assertEquals("Deleted video", result.tracks[1].unavailableReason)
        assertFalse(result.tracks[2].isAvailable)
        assertEquals("Private video", result.tracks[2].unavailableReason)
    }

    @Test
    fun `handles empty playlist`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "items": [{
                    "id": "PL_EMPTY_12345",
                    "snippet": { "title": "Empty List" },
                    "contentDetails": { "itemCount": 0 },
                    "status": { "privacyStatus": "public" }
                  }]
                }
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setBody("""{ "items": [] }"""))

        val result = client.fetchPlaylist("PL_EMPTY_12345", "fake_key")
        assertEquals("Empty List", result.metadata.title)
        assertTrue(result.tracks.isEmpty())
    }

    @Test(expected = YouTubeApiException::class)
    fun `throws YouTubeApiException on 429 rate limit`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{ "error": { "code": 429, "message": "Quota exceeded" } }"""
            )
        )
        client.fetchPlaylist("PL_RATE_12345", "fake_key")
    }

    @Test
    fun `parses iso8601 durations accurately`() {
        assertEquals(225, client.parseIso8601DurationSeconds("PT3M45S"))
        assertEquals(3725, client.parseIso8601DurationSeconds("PT1H2M5S"))
        assertEquals(45, client.parseIso8601DurationSeconds("PT45S"))
        assertEquals(null, client.parseIso8601DurationSeconds("invalid"))
    }
}
