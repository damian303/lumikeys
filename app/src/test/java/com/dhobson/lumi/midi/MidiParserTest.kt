package com.dhobson.lumi.midi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    private val messages = mutableListOf<Triple<Int, Int, Int>>()
    private val sysEx = mutableListOf<ByteArray>()
    private val parser = MidiParser(
        onShortMessage = { status, d1, d2 -> messages += Triple(status, d1, d2) },
        onSysEx = { sysEx += it },
    )

    private fun feed(vararg bytes: Int) {
        val data = ByteArray(bytes.size) { bytes[it].toByte() }
        parser.feed(data, 0, data.size)
    }

    @Test
    fun `parses the note-on, aftertouch and note-off captured from the keyboard`() {
        feed(0x90, 0x53, 0x49, 0xA0, 0x53, 0x07, 0x80, 0x53, 0x7F)
        assertEquals(
            listOf(Triple(0x90, 0x53, 0x49), Triple(0xA0, 0x53, 0x07), Triple(0x80, 0x53, 0x7F)),
            messages,
        )
    }

    @Test
    fun `handles a message split across buffers`() {
        feed(0x90, 0x3C)
        assertEquals(emptyList<Triple<Int, Int, Int>>(), messages)
        feed(0x64)
        assertEquals(listOf(Triple(0x90, 0x3C, 0x64)), messages)
    }

    @Test
    fun `honours running status`() {
        feed(0x90, 0x3C, 0x64, 0x3E, 0x50, 0x3C, 0x00)
        assertEquals(
            listOf(Triple(0x90, 0x3C, 0x64), Triple(0x90, 0x3E, 0x50), Triple(0x90, 0x3C, 0x00)),
            messages,
        )
    }

    @Test
    fun `one-data-byte messages`() {
        feed(0xC0, 0x05, 0xD3, 0x40, 0x41)
        assertEquals(listOf(Triple(0xC0, 0x05, 0), Triple(0xD3, 0x40, 0), Triple(0xD3, 0x41, 0)), messages)
    }

    @Test
    fun `real-time bytes inside a message are ignored`() {
        feed(0x90, 0xF8, 0x3C, 0xFE, 0x64)
        assertEquals(listOf(Triple(0x90, 0x3C, 0x64)), messages)
    }

    @Test
    fun `sysex is delivered whole and does not leak into short messages`() {
        val topologyRequest = intArrayOf(0xF0, 0x00, 0x21, 0x10, 0x77, 0x00, 0x01, 0x01, 0x00, 0x5D, 0xF7)
        feed(0x90, 0x3C, 0x64)
        feed(*topologyRequest.copyOfRange(0, 5))
        feed(*topologyRequest.copyOfRange(5, topologyRequest.size))
        feed(0x80, 0x3C, 0x00)
        assertEquals(listOf(Triple(0x90, 0x3C, 0x64), Triple(0x80, 0x3C, 0x00)), messages)
        assertEquals(1, sysEx.size)
        assertArrayEquals(ByteArray(topologyRequest.size) { topologyRequest[it].toByte() }, sysEx[0])
    }

    @Test
    fun `data bytes after sysex need a new status byte`() {
        feed(0x90, 0x3C, 0x64, 0xF0, 0x01, 0xF7, 0x3E, 0x50)
        assertEquals(listOf(Triple(0x90, 0x3C, 0x64)), messages)
    }

    @Test
    fun `stray data bytes before any status are ignored`() {
        feed(0x3C, 0x64, 0x90, 0x3C, 0x64)
        assertEquals(listOf(Triple(0x90, 0x3C, 0x64)), messages)
    }

    @Test
    fun `respects offset and count`() {
        val data = byteArrayOf(0x11, 0x22, 0x90.toByte(), 0x3C, 0x64, 0x33)
        parser.feed(data, 2, 3)
        assertEquals(listOf(Triple(0x90, 0x3C, 0x64)), messages)
    }
}
