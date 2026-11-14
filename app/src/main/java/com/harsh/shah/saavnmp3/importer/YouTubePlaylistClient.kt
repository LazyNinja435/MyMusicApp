package com.harsh.shah.saavnmp3.importer

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class YouTubePlaylistMetadata(
    val playlistId: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String?,
    val itemCount: Int
)

data class YouTubePlaylistTrack(
    val videoId: String,
    val position: Int,
    val title: String,
    val channelTitle: String?,
    val videoOwnerChannelTitle: String?,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
    val isAvailable: Boolean,
    val unavailableReason: String? = null
)

data class YouTubePlaylistFetchResult(
    val metadata: YouTubePlaylistMetadata,
    val tracks: List<YouTubePlaylistTrack>
)

class YouTubeApiException(
    val httpCode: Int,
    message: String,
    val isRateLimited: Boolean = false,
    val isAuthOrKeyError: Boolean = false
) : IOException(message)

interface YouTubePlaylistDataSource {
    @Throws(IOException::class)
    fun fetchPlaylist(
        playlistId: String,
        apiKey: String,
        onPageFetched: ((fetchedCount: Int, totalExpected: Int?) -> Unit)? = null
    ): YouTubePlaylistFetchResult
}

class YouTubePlaylistClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) : YouTubePlaylistDataSource {

    companion object {
        const val DEFAULT_BASE_URL = "https://www.googleapis.com/youtube/v3"
        private const val MAX_PAGE_SIZE = 50
    }

    override fun fetchPlaylist(
        playlistId: String,
        apiKey: String,
        onPageFetched: ((fetchedCount: Int, totalExpected: Int?) -> Unit)?
    ): YouTubePlaylistFetchResult {
        if (apiKey.isBlank()) {
            throw YouTubeApiException(
                httpCode = 400,
                message = "YouTube Data API v3 key is not configured.",
                isAuthOrKeyError = true
            )
        }

        val metadata = fetchPlaylistMetadata(playlistId, apiKey)
        val rawTracks = fetchAllPlaylistItems(playlistId, apiKey, metadata.itemCount, onPageFetched)
        val durationsAndChannels = fetchVideoDetails(
            rawTracks.filter { it.isAvailable }.map { it.videoId }.distinct(),
            apiKey
        )

        val enrichedTracks = rawTracks.map { track ->
            val detail = durationsAndChannels[track.videoId]
            if (detail != null) {
                track.copy(
                    durationSeconds = detail.durationSeconds ?: track.durationSeconds,
                    videoOwnerChannelTitle = track.videoOwnerChannelTitle ?: detail.channelTitle
                )
            } else {
                track
            }
        }

        return YouTubePlaylistFetchResult(metadata = metadata, tracks = enrichedTracks)
    }

    private fun fetchPlaylistMetadata(playlistId: String, apiKey: String): YouTubePlaylistMetadata {
        val url = "$baseUrl/playlists".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet,contentDetails,status")
            .addQueryParameter("id", playlistId)
            .addQueryParameter("key", apiKey)
            .build()

        val body = executeGet(url.toString())
        val parsed = gson.fromJson(body, PlaylistsResponse::class.java)
        val item = parsed?.items?.firstOrNull()
            ?: throw YouTubeApiException(404, "Playlist not found or is private.")

        val privacy = item.status?.privacyStatus?.lowercase()
        if (privacy == "private") {
            throw YouTubeApiException(403, "This YouTube playlist is private.")
        }

        val snippet = item.snippet
        val thumb = snippet?.thumbnails?.high?.url
            ?: snippet?.thumbnails?.medium?.url
            ?: snippet?.thumbnails?.default?.url

        return YouTubePlaylistMetadata(
            playlistId = playlistId,
            title = snippet?.title?.takeIf { it.isNotBlank() } ?: "YouTube Playlist",
            description = snippet?.description.orEmpty(),
            thumbnailUrl = thumb,
            itemCount = item.contentDetails?.itemCount ?: 0
        )
    }

    private fun fetchAllPlaylistItems(
        playlistId: String,
        apiKey: String,
        expectedTotal: Int,
        onPageFetched: ((fetchedCount: Int, totalExpected: Int?) -> Unit)?
    ): List<YouTubePlaylistTrack> {
        val results = ArrayList<YouTubePlaylistTrack>()
        var pageToken: String? = null
        var positionCounter = 0

        do {
            val urlBuilder = "$baseUrl/playlistItems".toHttpUrl().newBuilder()
                .addQueryParameter("part", "snippet,contentDetails,status")
                .addQueryParameter("maxResults", MAX_PAGE_SIZE.toString())
                .addQueryParameter("playlistId", playlistId)
                .addQueryParameter("key", apiKey)
            if (!pageToken.isNullOrBlank()) {
                urlBuilder.addQueryParameter("pageToken", pageToken)
            }

            val body = executeGet(urlBuilder.build().toString())
            val parsed = gson.fromJson(body, PlaylistItemsResponse::class.java)
            val items = parsed?.items.orEmpty()

            for (item in items) {
                val snippet = item.snippet
                val videoId = item.contentDetails?.videoId
                    ?: snippet?.resourceId?.videoId
                    ?: ""
                val rawTitle = snippet?.title.orEmpty()
                val privacy = item.status?.privacyStatus?.lowercase()

                val isUnavailable = videoId.isBlank() ||
                    privacy == "private" ||
                    privacy == "privacystatusunspecified" ||
                    rawTitle.equals("Private video", ignoreCase = true) ||
                    rawTitle.equals("Deleted video", ignoreCase = true)

                val reason = when {
                    rawTitle.equals("Deleted video", ignoreCase = true) -> "Deleted video"
                    rawTitle.equals("Private video", ignoreCase = true) || privacy == "private" -> "Private video"
                    videoId.isBlank() || privacy == "privacystatusunspecified" -> "Unavailable video"
                    else -> null
                }

                val thumb = snippet?.thumbnails?.high?.url
                    ?: snippet?.thumbnails?.medium?.url
                    ?: snippet?.thumbnails?.default?.url

                results.add(
                    YouTubePlaylistTrack(
                        videoId = videoId,
                        position = snippet?.position ?: positionCounter,
                        title = rawTitle.ifBlank { reason ?: "Unavailable video" },
                        channelTitle = snippet?.channelTitle,
                        videoOwnerChannelTitle = snippet?.videoOwnerChannelTitle,
                        durationSeconds = null,
                        thumbnailUrl = thumb,
                        isAvailable = !isUnavailable,
                        unavailableReason = reason
                    )
                )
                positionCounter++
            }

            onPageFetched?.invoke(results.size, if (expectedTotal > 0) expectedTotal else null)
            pageToken = parsed?.nextPageToken
        } while (!pageToken.isNullOrBlank())

        return results
    }

    private data class VideoDetail(val durationSeconds: Int?, val channelTitle: String?)

    private fun fetchVideoDetails(videoIds: List<String>, apiKey: String): Map<String, VideoDetail> {
        if (videoIds.isEmpty()) return emptyMap()
        val details = HashMap<String, VideoDetail>()

        for (chunk in videoIds.chunked(MAX_PAGE_SIZE)) {
            val url = "$baseUrl/videos".toHttpUrl().newBuilder()
                .addQueryParameter("part", "contentDetails,snippet")
                .addQueryParameter("id", chunk.joinToString(","))
                .addQueryParameter("key", apiKey)
                .build()

            try {
                val body = executeGet(url.toString())
                val parsed = gson.fromJson(body, VideosResponse::class.java)
                for (item in parsed?.items.orEmpty()) {
                    val id = item.id ?: continue
                    val durationSec = parseIso8601DurationSeconds(item.contentDetails?.duration)
                    details[id] = VideoDetail(
                        durationSeconds = durationSec,
                        channelTitle = item.snippet?.channelTitle
                    )
                }
            } catch (_: Exception) {
                // Non-fatal: playlist item metadata is still usable without video duration enrichment
            }
        }
        return details
    }

    private fun executeGet(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                return body
            }
            val code = response.code
            val apiMsg = try {
                gson.fromJson(body, YouTubeErrorEnvelope::class.java)?.error?.message
            } catch (_: Exception) {
                null
            }
            val message = apiMsg ?: "YouTube API error (HTTP $code)"
            val isRateLimited = code == 429 || (code == 403 && message.contains("quota", ignoreCase = true))
            val isAuthError = code == 400 || code == 401 || (code == 403 && !isRateLimited)
            throw YouTubeApiException(
                httpCode = code,
                message = message,
                isRateLimited = isRateLimited,
                isAuthOrKeyError = isAuthError
            )
        }
    }

    internal fun parseIso8601DurationSeconds(iso: String?): Int? {
        if (iso.isNullOrBlank()) return null
        val match = Regex("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$").matchEntire(iso.trim())
            ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        val seconds = match.groupValues[3].toIntOrNull() ?: 0
        return hours * 3600 + minutes * 60 + seconds
    }

    // --- Internal Gson DTOs ---
    private data class PlaylistsResponse(val items: List<PlaylistResource>?)
    private data class PlaylistResource(
        val id: String?,
        val snippet: PlaylistSnippet?,
        val contentDetails: PlaylistContentDetails?,
        val status: PrivacyStatus?
    )
    private data class PlaylistSnippet(
        val title: String?,
        val description: String?,
        val thumbnails: Thumbnails?
    )
    private data class PlaylistContentDetails(val itemCount: Int?)
    private data class PrivacyStatus(val privacyStatus: String?)

    private data class PlaylistItemsResponse(
        val nextPageToken: String?,
        val items: List<PlaylistItemResource>?
    )
    private data class PlaylistItemResource(
        val snippet: PlaylistItemSnippet?,
        val contentDetails: PlaylistItemContentDetails?,
        val status: PrivacyStatus?
    )
    private data class PlaylistItemSnippet(
        val title: String?,
        val position: Int?,
        val channelTitle: String?,
        val videoOwnerChannelTitle: String?,
        val resourceId: ResourceId?,
        val thumbnails: Thumbnails?
    )
    private data class PlaylistItemContentDetails(val videoId: String?)
    private data class ResourceId(val videoId: String?)

    private data class VideosResponse(val items: List<VideoResource>?)
    private data class VideoResource(
        val id: String?,
        val snippet: VideoSnippet?,
        val contentDetails: VideoContentDetails?
    )
    private data class VideoSnippet(val channelTitle: String?)
    private data class VideoContentDetails(val duration: String?)

    private data class Thumbnails(
        val default: Thumbnail?,
        val medium: Thumbnail?,
        val high: Thumbnail?
    )
    private data class Thumbnail(val url: String?)

    private data class YouTubeErrorEnvelope(val error: YouTubeErrorDetail?)
    private data class YouTubeErrorDetail(val code: Int?, val message: String?)
}
