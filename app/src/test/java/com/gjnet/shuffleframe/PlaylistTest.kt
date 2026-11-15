package com.gjnet.shuffleframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

class PlaylistTest {
    private fun items(n: Int, albums: Int = 1) = (0 until n).map {
        PlayItem("k$it", "a${it % albums}", "Album", MediaType.PHOTO, File("x"), 0, 0, "")
    }

    @Test
    fun everyItemOncePerRound() {
        val p = Playlist(Random(1))
        p.setItems(items(50), balanced = false)
        val seen = (1..50).map { p.next()!!.key }
        assertEquals(50, seen.toSet().size)
    }

    @Test
    fun reshuffleKeepsShownHistoryAndStartsANewOrder() {
        val p = Playlist(Random(2))
        p.setItems(items(1000), balanced = false)
        val shown = (1..5).map { p.next()!!.key }   // A B C D E
        p.peekNext()                                  // a look-ahead, never shown
        repeat(3) { p.previous() }                    // back at B
        p.reshuffle()
        val fresh = p.next()!!.key
        assertFalse("a new item after the resume", fresh in shown)
        // Back walks through everything that was shown: E D C B A.
        assertEquals(shown.reversed(), (1..5).map { p.previous()!!.key })
        assertFalse(p.hasPrevious)
    }

    @Test
    fun reshuffleRefillsTheWholeBag() {
        val p = Playlist(Random(3))
        p.setItems(items(20), balanced = false)
        repeat(15) { p.next() }
        p.reshuffle()
        // After a reshuffle, a full round of 20 shows all 20 again, not just the 5 left over.
        assertEquals(20, (1..20).map { p.next()!!.key }.toSet().size)
    }

    @Test
    fun noImmediateRepeatAcrossReshuffle() {
        repeat(200) { seed ->
            val p = Playlist(Random(seed.toLong()))
            p.setItems(items(2), balanced = false)
            val last = p.next()!!.key
            p.reshuffle()
            assertNotEquals(last, p.next()!!.key)
        }
    }

    @Test
    fun hasPreviousTracksPosition() {
        val p = Playlist(Random(4))
        p.setItems(items(10), balanced = false)
        p.next()
        assertFalse(p.hasPrevious)
        p.next()
        assertTrue(p.hasPrevious)
        p.previous()
        assertFalse(p.hasPrevious)
    }
}
