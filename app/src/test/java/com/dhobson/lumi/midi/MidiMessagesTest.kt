package com.dhobson.lumi.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiMessagesTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun split(vararg values: Int): List<String> =
        MidiMessages.split(bytes(*values)).map { m -> m.joinToString(" ") { "%02X".format(it) } }

    @Test
    fun `splits a burst of note-ons into single messages`() {
        assertEquals(
            listOf("90 3C 40", "90 3E 40", "90 40 40"),
            split(0x90, 0x3C, 0x40, 0x90, 0x3E, 0x40, 0x90, 0x40, 0x40),
        )
    }

    @Test
    fun `two-byte and three-byte messages mixed`() {
        assertEquals(
            listOf("C0 05", "90 3C 40", "D0 7F", "E0 00 40", "A0 40 08"),
            split(0xC0, 0x05, 0x90, 0x3C, 0x40, 0xD0, 0x7F, 0xE0, 0x00, 0x40, 0xA0, 0x40, 0x08),
        )
    }

    @Test
    fun `sysex stays whole, between channel messages`() {
        assertEquals(
            listOf("90 3C 40", "F0 00 21 10 77 00 01 01 00 5D F7", "80 3C 00"),
            split(0x90, 0x3C, 0x40, 0xF0, 0x00, 0x21, 0x10, 0x77, 0x00, 0x01, 0x01, 0x00, 0x5D, 0xF7, 0x80, 0x3C, 0x00),
        )
    }

    @Test
    fun `running status is expanded`() {
        assertEquals(listOf("90 3C 40", "90 3E 40", "90 3C 00"), split(0x90, 0x3C, 0x40, 0x3E, 0x40, 0x3C, 0x00))
        assertEquals(listOf("C0 01", "C0 02"), split(0xC0, 0x01, 0x02))
    }

    @Test
    fun `running status does not survive sysex`() {
        assertEquals(listOf("90 3C 40", "F0 01 F7"), split(0x90, 0x3C, 0x40, 0xF0, 0x01, 0xF7, 0x3E, 0x40))
    }

    @Test
    fun `truncated or unterminated input is dropped, not sent half-finished`() {
        assertEquals(listOf("90 3C 40"), split(0x90, 0x3C, 0x40, 0x90, 0x3E))
        assertEquals(listOf("90 3C 40"), split(0x90, 0x3C, 0x40, 0xF0, 0x00, 0x21))
        assertEquals(emptyList<String>(), split(0x3C, 0x40))
        assertEquals(emptyList<String>(), split())
    }

    @Test
    fun `real-time bytes are their own messages`() {
        assertEquals(listOf("F8", "90 3C 40"), split(0xF8, 0x90, 0x3C, 0x40))
    }
}
