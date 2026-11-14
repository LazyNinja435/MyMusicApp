package com.harsh.shah.saavnmp3.importer

import java.text.Normalizer

data class NormalizedTrackInfo(
    val rawTitle: String,
    val cleanTitle: String,
    val extractedArtist: String?,
    val extractedAlbumOrMovie: String?,
    val versionTags: Set<String>,
    val languageHint: String?,
    val searchQueries: List<String>
)

object SongTitleNormalizer {

    private val VERSION_KEYWORDS = listOf(
        "remix", "lofi", "lo-fi", "unplugged", "acoustic", "live",
        "instrumental", "karaoke", "cover", "slowed", "reverb",
        "reprise", "mashup", "dj mix", "club mix"
    )

    private val LANGUAGE_KEYWORDS = listOf(
        "telugu", "hindi", "tamil", "malayalam", "kannada", "punjabi", "english",
        "bengali", "marathi", "gujarati", "bhojpuri"
    )

    private val NOISE_PATTERNS = listOf(
        Regex("\\b(official\\s*(music\\s*)?(video|audio|lyric(al)?\\s*video|visualizer|track))\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(full\\s*(video\\s*song|audio\\s*song|song|video|audio))\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(lyrical\\s*(video\\s*song|video|song)?|with\\s*lyrics|lyric\\s*video|lyrics)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(video\\s*song|audio\\s*song|music\\s*video|theme\\s*song)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(4k|8k|hd|uhd|1080p|720p|hq|dolby|atmos|stereo)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(teaser|promo|trailer|jukebox|sneak\\s*peek|glimpse)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(starring\\s+[^|\\[\\]()]+)\\b", RegexOption.IGNORE_CASE)
    )

    private val LABEL_OR_ROLE_NOISE = listOf(
        "t-series", "sony music", "aditya music", "lahari music", "think music",
        "saregama", "zee music", "tips official", "yrf", "eros now", "sun pictures",
        "mythri movie makers", "geetha arts", "mango music", "junglee music",
        "speed records", "desi melodies", "vevo", "official"
    )

    private val CHANNEL_SUFFIX_REGEX = Regex("\\s*-\\s*Topic$|\\s*VEVO$|\\s*Official$", RegexOption.IGNORE_CASE)

    fun normalize(rawTitle: String, channelTitle: String? = null): NormalizedTrackInfo {
        val normalizedUnicode = normalizeUnicode(rawTitle)
        val versionTags = extractVersionTags(normalizedUnicode)
        val languageHint = extractLanguageHint(normalizedUnicode)

        val cleanedChannel = cleanChannelName(channelTitle)

        // Check for "(From \"Movie\")" pattern common on YouTube Music / streaming uploads
        var fromMovie: String? = null
        val fromMatch = Regex("\\((?:From|from)\\s*[\"']?([^\"')]+)[\"']?\\)").find(normalizedUnicode)
        if (fromMatch != null) {
            fromMovie = fromMatch.groupValues[1].trim()
        }

        // Split on pipe '|' first, which is standard for Indian film music titles
        val pipeParts = normalizedUnicode.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val primaryPart = pipeParts.firstOrNull() ?: normalizedUnicode

        var candidateTitle = primaryPart
        var extractedArtist: String? = null
        var extractedAlbum: String? = fromMovie

        // Strip "(From ...)" from primary part
        candidateTitle = candidateTitle.replace(Regex("\\((?:From|from)\\s*[\"']?[^\"')]+[\"']?\\)"), " ").trim()

        // Check for "Artist - Title" or "Title - Movie/Subtitle"
        val dashParts = candidateTitle.split(Regex("\\s+[-–—]\\s+"))
        if (dashParts.size >= 2) {
            val left = stripNoise(dashParts[0])
            val right = stripNoise(dashParts[1])

            if (cleanedChannel != null && tokensOverlap(left, cleanedChannel)) {
                extractedArtist = left
                candidateTitle = right
            } else if (isLikelySubtitleNoise(right)) {
                candidateTitle = left
            } else if (dashParts.size == 2 && isLikelyArtistName(left, cleanedChannel)) {
                extractedArtist = left
                candidateTitle = right
            } else {
                // In Indian YT titles: "Arabic Kuthu - Video Song" or "Naatu Naatu - Telugu"
                candidateTitle = left
                if (extractedAlbum == null && right.isNotBlank() && !isNoiseOnly(right)) {
                    extractedAlbum = right
                }
            }
        }

        // Inspect remaining pipe segments for movie/album & artist hints
        if (pipeParts.size > 1) {
            for (i in 1 until pipeParts.size) {
                val seg = stripNoise(pipeParts[i])
                if (seg.isBlank() || isNoiseOnly(seg)) continue
                if (extractedAlbum == null && i == 1) {
                    extractedAlbum = seg
                } else if (extractedArtist == null && cleanedChannel != null && tokensOverlap(seg, cleanedChannel)) {
                    extractedArtist = seg
                } else if (extractedArtist == null && i >= 2 && seg.split(" ").size <= 4) {
                    extractedArtist = seg
                }
            }
        }

        if (extractedArtist == null && cleanedChannel != null && !isRecordLabel(cleanedChannel)) {
            extractedArtist = cleanedChannel
        }

        val cleanTitle = stripNoise(candidateTitle)
            .replace(Regex("\\(\\s*\\)|\\[\\s*]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { stripNoise(normalizedUnicode).trim() }

        val cleanAlbum = extractedAlbum?.let { stripNoise(it).trim() }?.takeIf { it.isNotBlank() }
        val cleanArtist = extractedArtist?.let { stripNoise(it).trim() }?.takeIf { it.isNotBlank() }

        val queries = buildSearchQueries(cleanTitle, cleanArtist, cleanAlbum, languageHint)

        return NormalizedTrackInfo(
            rawTitle = rawTitle,
            cleanTitle = cleanTitle,
            extractedArtist = cleanArtist,
            extractedAlbumOrMovie = cleanAlbum,
            versionTags = versionTags,
            languageHint = languageHint,
            searchQueries = queries
        )
    }

    fun normalizeForComparison(text: String): String {
        val noDiacritics = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noDiacritics.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeUnicode(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u201C', '"')
            .replace('\u201D', '"')
            .trim()
    }

    private fun cleanChannelName(channelTitle: String?): String? {
        if (channelTitle.isNullOrBlank()) return null
        val cleaned = channelTitle.replace(CHANNEL_SUFFIX_REGEX, "").trim()
        return cleaned.takeIf { it.isNotBlank() && !isRecordLabel(it) }
    }

    private fun isRecordLabel(name: String): Boolean {
        val lower = name.lowercase()
        return LABEL_OR_ROLE_NOISE.any { lower.contains(it) }
    }

    private fun isLikelySubtitleNoise(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isEmpty()) return true
        val stripped = stripNoise(lower).trim()
        return stripped.isEmpty()
    }

    private fun isNoiseOnly(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.isEmpty() || isRecordLabel(lower) || stripNoise(lower).isBlank()
    }

    private fun isLikelyArtistName(left: String, channelName: String?): Boolean {
        if (channelName != null && tokensOverlap(left, channelName)) return true
        return false
    }

    private fun extractVersionTags(text: String): Set<String> {
        val lower = text.lowercase()
        return VERSION_KEYWORDS.filter { kw ->
            Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(lower)
        }.map { if (it == "lo-fi") "lofi" else it }.toSet()
    }

    private fun extractLanguageHint(text: String): String? {
        val lower = text.lowercase()
        return LANGUAGE_KEYWORDS.firstOrNull { lang ->
            Regex("\\b$lang\\b").containsMatchIn(lower)
        }
    }

    private fun stripNoise(text: String): String {
        var result = text
        // Remove feat. / ft. clauses inside brackets or trailing
        result = result.replace(Regex("[(\\[](?:feat\\.?|ft\\.?)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), " ")
        for (pattern in NOISE_PATTERNS) {
            result = pattern.replace(result, " ")
        }
        // Remove empty or noise-only brackets
        result = result.replace(Regex("\\[\\s*]"), " ")
        result = result.replace(Regex("\\(\\s*\\)"), " ")
        // Remove standalone language tags in brackets e.g. "(Telugu)" or "[Hindi]"
        for (lang in LANGUAGE_KEYWORDS) {
            result = result.replace(Regex("[(\\[]\\s*$lang\\s*[)\\]]", RegexOption.IGNORE_CASE), " ")
        }
        // Clean stray separators at edges
        result = result.replace(Regex("^[-–—:|\\s]+|[-–—:|\\s]+$"), "")
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun tokensOverlap(a: String, b: String): Boolean {
        val ta = normalizeForComparison(a).split(" ").filter { it.length > 1 }.toSet()
        val tb = normalizeForComparison(b).split(" ").filter { it.length > 1 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return false
        return ta.intersect(tb).isNotEmpty()
    }

    private fun buildSearchQueries(
        cleanTitle: String,
        artist: String?,
        album: String?,
        languageHint: String?
    ): List<String> {
        val queries = LinkedHashSet<String>()
        if (!artist.isNullOrBlank()) {
            queries.add("$cleanTitle $artist".trim())
        }
        if (!album.isNullOrBlank() && !album.equals(cleanTitle, ignoreCase = true)) {
            queries.add("$cleanTitle $album".trim())
        }
        if (!languageHint.isNullOrBlank() && !cleanTitle.contains(languageHint, ignoreCase = true)) {
            queries.add("$cleanTitle $languageHint".trim())
        }
        queries.add(cleanTitle)
        return queries.toList()
    }
}
