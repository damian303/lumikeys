package com.dhobson.lumi.music

import org.junit.Assert.assertEquals
import org.junit.Test

class InstrumentsTest {
    @Test
    fun `sixteen General MIDI families of eight, then drums`() {
        assertEquals(17, Instruments.families.size)
        assertEquals(List(16) { 8 }, Instruments.families.take(16).map { it.instruments.size })
        assertEquals(listOf(Instruments.drumKit), Instruments.families.last().instruments)
    }

    @Test
    fun `program numbers are 0 to 127 in order`() {
        val ids = Instruments.families.take(16).flatMap { it.instruments }.map { it.id }
        assertEquals((0..127).toList(), ids)
    }

    @Test
    fun `spot-check well-known General MIDI programs`() {
        assertEquals("Acoustic Grand Piano", Instruments.byId(0)?.name)
        assertEquals("Church Organ", Instruments.byId(19)?.name)
        assertEquals("Violin", Instruments.byId(40)?.name)
        assertEquals("Trumpet", Instruments.byId(56)?.name)
        assertEquals("Flute", Instruments.byId(73)?.name)
        assertEquals("Gunshot", Instruments.byId(127)?.name)
    }

    @Test
    fun `drums use channel 10 and everything else channel 1`() {
        assertEquals(9, Instruments.channelFor(Instruments.DRUM_KIT_ID))
        assertEquals(0, Instruments.channelFor(0))
        assertEquals(0, Instruments.channelFor(127))
    }
}
