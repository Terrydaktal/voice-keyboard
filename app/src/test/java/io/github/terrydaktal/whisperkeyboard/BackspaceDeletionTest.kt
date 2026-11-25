package io.github.terrydaktal.whisperkeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class BackspaceDeletionTest {
    @Test
    fun singleCharDeletesOneCodeUnitOrFullSurrogatePair() {
        assertEquals(0, BackspaceDeletion.singleCharDeleteCount(""))
        assertEquals(1, BackspaceDeletion.singleCharDeleteCount("a"))
        assertEquals(1, BackspaceDeletion.singleCharDeleteCount("ab"))
        // U+1F600 (😀) is a UTF-16 surrogate pair of length 2
        assertEquals(2, BackspaceDeletion.singleCharDeleteCount("\uD83D\uDE00"))
    }

    @Test
    fun wordDeleteRemovesWordAndTrailingSpacesLikeSwiftKey() {
        assertEquals(0, BackspaceDeletion.wordDeleteCount(""))
        assertEquals("world".length, BackspaceDeletion.wordDeleteCount("Hello world"))
        assertEquals("Hello ".length, BackspaceDeletion.wordDeleteCount("Hello "))
        assertEquals("world. ".length, BackspaceDeletion.wordDeleteCount("Hello world. "))
        assertEquals("don't ".length, BackspaceDeletion.wordDeleteCount("I don't "))
        assertEquals("   ".length, BackspaceDeletion.wordDeleteCount("   "))
    }

    @Test
    fun wordDeleteRespectsLineBoundaries() {
        assertEquals("2".length, BackspaceDeletion.wordDeleteCount("line 1\nline 2"))
        assertEquals("line ".length, BackspaceDeletion.wordDeleteCount("line 1\nline "))
        assertEquals(1, BackspaceDeletion.wordDeleteCount("line 1\n"))
        assertEquals(2, BackspaceDeletion.wordDeleteCount("line 1\r\n"))
        // Trailing spaces on a new line stop at the newline instead of wiping the previous line
        assertEquals(3, BackspaceDeletion.wordDeleteCount("line 1\n   "))
    }
}
