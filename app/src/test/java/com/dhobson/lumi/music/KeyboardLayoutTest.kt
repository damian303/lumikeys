package com.dhobson.lumi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardLayoutTest {
    // 14 white keys, each 100 wide; black keys cover the top 62% and are 60 wide.
    private val width = 1400f
    private val height = 200f
    private val base = 60

    private fun at(x: Float, y: Float) = KeyboardLayout.noteAt(x, y, width, height, base)

    @Test
    fun `white keys along the bottom edge`() {
        val expected = listOf(60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 77, 79, 81, 83)
        val actual = (0 until 14).map { at(it * 100f + 50f, 190f) }
        assertEquals(expected, actual)
    }

    @Test
    fun `black keys sit on the boundaries between white keys`() {
        assertEquals(61, at(100f, 10f)) // C#
        assertEquals(63, at(200f, 10f)) // D#
        assertEquals(66, at(400f, 10f)) // F#
        assertEquals(68, at(500f, 10f)) // G#
        assertEquals(70, at(600f, 10f)) // A#
        assertEquals(73, at(800f, 10f)) // C# in the second octave
    }

    @Test
    fun `no black key between E and F or between B and C`() {
        assertEquals(64, at(295f, 10f)) // E side of the E/F boundary
        assertEquals(65, at(305f, 10f)) // F side
        assertEquals(71, at(695f, 10f)) // B side of the B/C boundary
        assertEquals(72, at(705f, 10f)) // C side
    }

    @Test
    fun `black key edges and height`() {
        assertEquals(61, at(71f, 10f))   // just inside the left edge of C#
        assertEquals(60, at(69f, 10f))   // just outside: still C
        assertEquals(61, at(129f, 10f))  // just inside the right edge
        assertEquals(62, at(131f, 10f))  // just outside: D
        assertEquals(61, at(100f, 123f)) // above the bottom of the black key
        assertEquals(62, at(100f, 125f)) // below it: the white key under the boundary
    }

    @Test
    fun `touches outside the keyboard clamp to the end keys`() {
        assertEquals(60, at(-50f, 190f))
        assertEquals(83, at(5000f, 190f))
    }

    @Test
    fun `every key is reachable exactly once`() {
        val whites = (0 until KeyboardLayout.WHITE_KEYS).map { KeyboardLayout.whiteNote(base, it) }
        val blacks = (0 until KeyboardLayout.WHITE_KEYS).mapNotNull { KeyboardLayout.blackNoteAfter(base, it) }
        assertEquals((60..83).toList(), (whites + blacks).sorted())
        assertNull(KeyboardLayout.blackNoteAfter(base, 13)) // nothing right of the last B
    }

    @Test
    fun `base note follows the hardware keyboard by whole octaves`() {
        assertEquals(48, KeyboardLayout.baseNoteContaining(48, 48))
        assertEquals(48, KeyboardLayout.baseNoteContaining(48, 71))
        assertEquals(60, KeyboardLayout.baseNoteContaining(48, 72))
        assertEquals(60, KeyboardLayout.baseNoteContaining(48, 83))
        assertEquals(36, KeyboardLayout.baseNoteContaining(48, 47))
        assertEquals(108, KeyboardLayout.baseNoteContaining(48, 127)) // capped at the top
        assertEquals(0, KeyboardLayout.baseNoteContaining(48, 0))
    }

    @Test
    fun `note names`() {
        assertEquals("C4", KeyboardLayout.noteName(60))
        assertEquals("B5", KeyboardLayout.noteName(83))
        assertEquals("C#3", KeyboardLayout.noteName(49))
    }
}
