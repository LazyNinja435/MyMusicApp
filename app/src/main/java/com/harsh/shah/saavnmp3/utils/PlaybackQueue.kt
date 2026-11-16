package com.harsh.shah.saavnmp3.utils

import com.harsh.shah.saavnmp3.records.SongResponse
import com.harsh.shah.saavnmp3.records.sharedpref.SavedLibraries

data class QueueTrack(
    val id: String,
    val title: String? = null,
    val artist: String? = null,
    val artwork: String? = null
)

fun SongResponse.Song.toQueueTrack(fallbackArtwork: String? = null): QueueTrack? =
    id?.takeUnless { it.isBlank() || it == "<shimmer>" }?.let {
        val credits = artists?.all.orEmpty().ifEmpty { artists?.primary.orEmpty() }
            .mapNotNull { artist -> artist?.name()?.takeIf(String::isNotBlank) }.distinct()
        QueueTrack(it, name(), credits.joinToString(", "), image?.lastOrNull()?.url ?: fallbackArtwork)
    }

fun SavedLibraries.Library.Songs.toQueueTrack(): QueueTrack? =
    id?.takeIf(String::isNotBlank)?.let { QueueTrack(it, title, description, image) }

internal class PlaybackQueue {
    class Entry(val key: Long, var track: QueueTrack, val recommended: Boolean)

    private var nextKey = 0L
    private val original = mutableListOf<Entry>()
    private var shuffled: MutableList<Entry>? = null
    val entries: List<Entry> get() = shuffled ?: original

    fun replace(tracks: List<QueueTrack?>, startIndex: Int, shuffle: Boolean): Entry? {
        original.clear()
        shuffled = null
        var selected: Entry? = null
        tracks.forEachIndexed { index, track ->
            if (track != null) {
                val entry = Entry(++nextKey, track, recommended = false)
                original.add(entry)
                if (index == startIndex) selected = entry
            }
        }
        val start = selected ?: original.firstOrNull()
        setShuffle(shuffle, start)
        return start
    }

    fun setShuffle(enabled: Boolean, current: Entry?) {
        shuffled = if (enabled) {
            original.filter { it != current }.shuffled().toMutableList().apply {
                if (current in original) add(0, requireNotNull(current))
            }
        } else {
            null
        }
    }

    fun enqueue(track: QueueTrack, after: Entry?, playNext: Boolean): Entry? {
        val existing = original.firstOrNull { it.track.id == track.id }
        if (existing != null && (!playNext || existing == after)) return null
        val entry = existing ?: Entry(++nextKey, track, recommended = false)
        fun insert(order: MutableList<Entry>) {
            order.remove(entry)
            val anchor = order.indexOf(after)
            order.add(if (playNext && anchor >= 0) anchor + 1 else order.size, entry)
        }
        insert(original)
        shuffled?.let(::insert)
        return entry
    }

    fun appendRecommendations(tracks: List<QueueTrack>) {
        val entries = tracks.map { Entry(++nextKey, it, recommended = true) }
        original.addAll(entries)
        shuffled?.addAll(entries)
    }

    fun remove(entry: Entry) {
        original.remove(entry)
        shuffled?.remove(entry)
    }

    fun move(entry: Entry, target: Entry) {
        val order = shuffled ?: original
        val position = order.indexOf(target)
        if (position >= 0 && order.remove(entry)) order.add(position, entry)
    }

    fun recommendationSeedIds(): List<String> {
        val playlist = original.filterNot { it.recommended }
        return listOfNotNull(playlist.firstOrNull(), playlist.getOrNull(playlist.size / 2))
            .map { it.track.id }.distinct()
    }
}
