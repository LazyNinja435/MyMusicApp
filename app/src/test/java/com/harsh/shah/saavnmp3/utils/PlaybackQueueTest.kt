package com.harsh.shah.saavnmp3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackQueueTest {
    @Test
    fun shuffleStartsAtCurrentEntryAndRestoreKeepsExactPositions() {
        val queue = PlaybackQueue()
        val current = queue.replace(listOf(QueueTrack("duplicate"), QueueTrack("b"), QueueTrack("duplicate")), 2, false)
        val original = queue.entries.toList()
        assertSame(original[2], current)

        queue.setShuffle(true, current)
        assertSame(current, queue.entries.first())
        assertEquals(original.toSet(), queue.entries.toSet())

        queue.setShuffle(false, current)
        assertEquals(original, queue.entries)
    }

    @Test
    fun tracksAddedDuringShuffleKeepTheirOriginalPositionsAfterRestore() {
        val queue = PlaybackQueue()
        val current = queue.replace(listOf(QueueTrack("a"), QueueTrack("b"), QueueTrack("c")), 1, true)
        val next = queue.enqueue(QueueTrack("d"), current, playNext = true)
        assertNotNull(next)
        assertEquals(listOf("b", "d"), queue.entries.take(2).map { it.track.id })

        queue.appendRecommendations(listOf(QueueTrack("recommendation")))
        queue.remove(queue.entries.first { it.track.id == "a" })
        queue.setShuffle(false, current)

        assertEquals(listOf("b", "d", "c", "recommendation"), queue.entries.map { it.track.id })
        assertEquals(listOf("b", "d"), queue.recommendationSeedIds())
        assertEquals(listOf("recommendation"), queue.entries.filter { it.recommended }.map { it.track.id })
    }
}
