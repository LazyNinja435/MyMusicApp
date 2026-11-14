package com.harsh.shah.saavnmp3.importer

import android.content.Context
import com.google.gson.Gson
import com.harsh.shah.saavnmp3.network.ApiManager
import com.harsh.shah.saavnmp3.network.utility.RequestNetwork
import com.harsh.shah.saavnmp3.records.SongResponse.Song
import com.harsh.shah.saavnmp3.records.SongSearch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max

interface JioSaavnSearchDataSource {
    suspend fun searchSongs(query: String, limit: Int = 10): List<Song>
}

class ApiManagerJioSaavnSearch(
    private val context: Context,
    private val gson: Gson = Gson()
) : JioSaavnSearchDataSource {
    override suspend fun searchSongs(query: String, limit: Int): List<Song> {
        return suspendCancellableCoroutine { cont ->
            ApiManager(context).searchSongs(query, 0, limit, object : RequestNetwork.RequestListener {
                override fun onResponse(tag: String?, response: String?, responseHeaders: HashMap<String?, Any?>?) {
                    val parsed = try {
                        gson.fromJson(response, SongSearch::class.java)
                    } catch (e: Exception) {
                        null
                    }
                    val songs = parsed?.data?.results?.filterNotNull().orEmpty()
                    if (cont.isActive) cont.resume(songs)
                }

                override fun onErrorResponse(tag: String?, message: String?) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            })
        }
    }
}

enum class MatchConfidenceStatus {
    MATCHED,
    NEEDS_REVIEW,
    FAILED
}

data class ScoredSongCandidate(
    val song: Song,
    val score: Double,
    val explanation: String
)

data class SongMatchResult(
    val status: MatchConfidenceStatus,
    val bestCandidate: ScoredSongCandidate?,
    val candidates: List<ScoredSongCandidate>,
    val normalizedInfo: NormalizedTrackInfo,
    val failureReason: String? = null
)

class JioSaavnSongMatcher(
    private val searchDataSource: JioSaavnSearchDataSource,
    private val autoMatchThreshold: Double = DEFAULT_AUTO_MATCH_THRESHOLD,
    private val reviewThreshold: Double = DEFAULT_REVIEW_THRESHOLD
) {
    companion object {
        const val DEFAULT_AUTO_MATCH_THRESHOLD = 0.72
        const val DEFAULT_REVIEW_THRESHOLD = 0.42
    }

    suspend fun match(track: YouTubePlaylistTrack): SongMatchResult {
        val channelHint = track.videoOwnerChannelTitle ?: track.channelTitle
        val normalized = SongTitleNormalizer.normalize(track.title, channelHint)

        val seenIds = LinkedHashMap<String, Song>()
        for (query in normalized.searchQueries.take(3)) {
            if (query.isBlank()) continue
            val results = searchDataSource.searchSongs(query, limit = 10)
            for (song in results) {
                val id = song.id ?: continue
                if (!seenIds.containsKey(id)) {
                    seenIds[id] = song
                }
            }
            // Early stop if we already found a very strong candidate
            val currentBest = seenIds.values.maxOfOrNull { scoreCandidate(track, normalized, it).score } ?: 0.0
            if (currentBest >= 0.90) break
        }

        if (seenIds.isEmpty()) {
            return SongMatchResult(
                status = MatchConfidenceStatus.FAILED,
                bestCandidate = null,
                candidates = emptyList(),
                normalizedInfo = normalized,
                failureReason = "No JioSaavn results found for '${normalized.cleanTitle}'"
            )
        }

        val scored = seenIds.values
            .map { scoreCandidate(track, normalized, it) }
            .sortedByDescending { it.score }
            .take(5)

        val best = scored.firstOrNull()
        if (best == null || best.score < reviewThreshold) {
            return SongMatchResult(
                status = MatchConfidenceStatus.FAILED,
                bestCandidate = best,
                candidates = scored,
                normalizedInfo = normalized,
                failureReason = "Best match confidence (${((best?.score ?: 0.0) * 100).toInt()}%) below threshold"
            )
        }

        val status = if (best.score >= autoMatchThreshold) {
            MatchConfidenceStatus.MATCHED
        } else {
            MatchConfidenceStatus.NEEDS_REVIEW
        }

        return SongMatchResult(
            status = status,
            bestCandidate = if (status == MatchConfidenceStatus.MATCHED) best else null,
            candidates = scored,
            normalizedInfo = normalized
        )
    }

    internal fun scoreCandidate(
        track: YouTubePlaylistTrack,
        normalized: NormalizedTrackInfo,
        candidate: Song
    ): ScoredSongCandidate {
        val candidateTitleRaw = candidate.name()
        val candidateTitleClean = SongTitleNormalizer.normalize(candidateTitleRaw).cleanTitle
        val titleScore = textSimilarity(normalized.cleanTitle, candidateTitleClean)

        val candidateArtists = buildList {
            candidate.artists?.primary?.forEach { it?.name()?.let(::add) }
            candidate.artists?.featured?.forEach { it?.name()?.let(::add) }
            candidate.artists?.all?.forEach { it?.name()?.let(::add) }
        }.distinct()

        val artistScore = if (!normalized.extractedArtist.isNullOrBlank() && candidateArtists.isNotEmpty()) {
            candidateArtists.maxOf { textSimilarity(normalized.extractedArtist, it) }
        } else {
            0.5 // Neutral when artist metadata is absent on one side
        }

        val candidateAlbum = candidate.album?.name().orEmpty()
        val albumScore = if (!normalized.extractedAlbumOrMovie.isNullOrBlank() && candidateAlbum.isNotBlank()) {
            textSimilarity(normalized.extractedAlbumOrMovie, candidateAlbum)
        } else {
            0.5
        }

        val ytDuration = track.durationSeconds
        val saavnDuration = candidate.duration?.toInt()
        val durationScore = if (ytDuration != null && ytDuration > 0 && saavnDuration != null && saavnDuration > 0) {
            val diff = abs(ytDuration - saavnDuration)
            when {
                diff <= 3 -> 1.0
                diff <= 8 -> 0.85
                diff <= 15 -> 0.6
                diff <= 30 -> 0.3
                else -> 0.0
            }
        } else {
            0.5
        }

        // Weighted base score
        var total = (titleScore * 0.48) +
            (artistScore * 0.24) +
            (albumScore * 0.14) +
            (durationScore * 0.14)

        // Version tag penalty (remix/lofi/live/cover/instrumental mismatch)
        val candidateVersionTags = SongTitleNormalizer.normalize(candidateTitleRaw).versionTags
        val reasons = ArrayList<String>()
        reasons.add("Title ${(titleScore * 100).toInt()}%")

        if (normalized.versionTags != candidateVersionTags) {
            if (candidateVersionTags.isNotEmpty() && normalized.versionTags.isEmpty()) {
                total -= 0.28
                reasons.add("Version mismatch (${candidateVersionTags.joinToString()})")
            } else if (normalized.versionTags.isNotEmpty() && candidateVersionTags.isEmpty()) {
                total -= 0.22
                reasons.add("Missing version (${normalized.versionTags.joinToString()})")
            }
        }

        // Language mismatch penalty when explicitly tagged on YouTube
        val ytLang = normalized.languageHint
        val saavnLang = candidate.language?.lowercase()?.trim()
        if (!ytLang.isNullOrBlank() && !saavnLang.isNullOrBlank() && ytLang != saavnLang) {
            total -= 0.28
            reasons.add("Language mismatch ($ytLang vs $saavnLang)")
        } else if (!ytLang.isNullOrBlank() && ytLang == saavnLang) {
            total += 0.05
        }

        // Guard: a pure title mismatch should never auto-match even if artist/album match
        if (titleScore < 0.45) {
            total = minOf(total, 0.55)
        }

        val clamped = total.coerceIn(0.0, 1.0)
        return ScoredSongCandidate(
            song = candidate,
            score = clamped,
            explanation = reasons.joinToString(" • ")
        )
    }

    internal fun textSimilarity(a: String, b: String): Double {
        val na = SongTitleNormalizer.normalizeForComparison(a)
        val nb = SongTitleNormalizer.normalizeForComparison(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0

        val tokensA = na.split(" ").filter { it.isNotEmpty() }.toSet()
        val tokensB = nb.split(" ").filter { it.isNotEmpty() }.toSet()
        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val union = tokensA.union(tokensB).size.toDouble()
        val jaccard = if (union > 0) intersection / union else 0.0

        val containment = if (na.contains(nb) || nb.contains(na)) {
            minOf(na.length, nb.length).toDouble() / maxOf(na.length, nb.length).toDouble()
        } else {
            0.0
        }

        val levenshtein = levenshteinSimilarity(na, nb)
        return max(jaccard * 0.5 + levenshtein * 0.5, max(containment * 0.9, levenshtein))
    }

    private fun levenshteinSimilarity(s1: String, s2: String): Double {
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(s1, s2)
        return (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                dp[j] = if (s1[i - 1] == s2[j - 1]) {
                    prev
                } else {
                    1 + minOf(prev, minOf(dp[j], dp[j - 1]))
                }
                prev = temp
            }
        }
        return dp[s2.length]
    }
}
