package com.harsh.shah.saavnmp3.utils

import com.harsh.shah.saavnmp3.records.SongResponse
import java.text.Normalizer

private val soundtrackCredit = Regex("""\s*\(\s*from\s+[^()]+\)\s*$""", RegexOption.IGNORE_CASE)
private val titleSeparators = Regex("""[^\p{L}\p{M}\p{N}]+""")

private fun recommendationTitleKey(title: String): String =
    Normalizer.normalize(title, Normalizer.Form.NFKC)
        .replace(soundtrackCredit, "")
        .lowercase()
        .replace(titleSeparators, " ")
        .trim()

internal fun uniqueSongRecommendations(
    candidates: List<SongResponse.Song?>,
    seed: SongResponse.Song?,
    queuedTracks: Map<String, String>,
    limit: Int
): List<SongResponse.Song> {
    val seenIds = queuedTracks.keys.toMutableSet()
    seed?.id?.let(seenIds::add)
    val seenTitles = queuedTracks.values.mapTo(mutableSetOf(), ::recommendationTitleKey)
    seed?.let { seenTitles.add(recommendationTitleKey(it.name())) }

    return candidates.asSequence().filterNotNull().filter { song ->
        val id = song.id?.takeIf { it.isNotBlank() } ?: return@filter false
        val title = recommendationTitleKey(song.name())
        if (id in seenIds || (title.isNotEmpty() && title in seenTitles)) return@filter false
        seenIds.add(id)
        seenTitles.add(title)
        true
    }.take(limit).toList()
}
