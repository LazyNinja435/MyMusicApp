package com.harsh.shah.saavnmp3.importer

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

sealed class PlaylistUrlParseResult {
    data class Success(
        val playlistId: String,
        val normalizedUrl: String,
        val isYouTubeMusic: Boolean
    ) : PlaylistUrlParseResult()

    data class Invalid(val reason: String) : PlaylistUrlParseResult()
}

object PlaylistUrlParser {

    private val ALLOWED_HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be"
    )

    private val PLAYLIST_ID_REGEX = Regex("^[A-Za-z0-9_-]{10,}$")
    private val PRIVATE_SYSTEM_LISTS = setOf("WL", "LL")

    fun parse(rawInput: String?): PlaylistUrlParseResult {
        val trimmed = rawInput?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return PlaylistUrlParseResult.Invalid("Please enter a YouTube or YouTube Music playlist URL.")
        }

        val withScheme = if (!trimmed.contains("://")) "https://$trimmed" else trimmed

        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return PlaylistUrlParseResult.Invalid("Invalid URL format.")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return PlaylistUrlParseResult.Invalid("Unsupported URL scheme.")
        }

        val host = uri.host?.lowercase()?.removeSuffix(".") ?: ""
        if (host !in ALLOWED_HOSTS) {
            return PlaylistUrlParseResult.Invalid("URL must be from YouTube or YouTube Music.")
        }

        val queryParams = parseQueryParams(uri.rawQuery)
        val rawListId = queryParams["list"]?.trim().orEmpty()

        if (rawListId.isEmpty()) {
            return PlaylistUrlParseResult.Invalid("No playlist ID ('list' parameter) found in URL.")
        }

        if (rawListId.uppercase() in PRIVATE_SYSTEM_LISTS) {
            return PlaylistUrlParseResult.Invalid("Personal Watch Later or Liked Videos lists cannot be imported without private access.")
        }

        if (!PLAYLIST_ID_REGEX.matches(rawListId)) {
            return PlaylistUrlParseResult.Invalid("Invalid YouTube playlist ID: '$rawListId'.")
        }

        val isYtMusic = host == "music.youtube.com"
        val normalizedUrl = if (isYtMusic) {
            "https://music.youtube.com/playlist?list=$rawListId"
        } else {
            "https://www.youtube.com/playlist?list=$rawListId"
        }

        return PlaylistUrlParseResult.Success(
            playlistId = rawListId,
            normalizedUrl = normalizedUrl,
            isYouTubeMusic = isYtMusic
        )
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key = if (idx > 0) decode(pair.substring(0, idx)) else decode(pair)
            val value = if (idx > 0 && idx < pair.length - 1) decode(pair.substring(idx + 1)) else ""
            if (key.isNotEmpty() && !result.containsKey(key)) {
                result[key] = value
            }
        }
        return result
    }

    private fun decode(s: String): String {
        return try {
            URLDecoder.decode(s, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            s
        }
    }
}
