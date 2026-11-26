package com.dhobson.lumi.loop

import com.dhobson.lumi.learn.MidiFileReader
import com.dhobson.lumi.learn.MidiNote
import com.dhobson.lumi.music.Instruments
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopMidiTest {
    private fun on(tick: Int, note: Int, velocity: Int = 100) = LoopEvent(tick, 0x90, note, velocity)
    private fun off(tick: Int, note: Int) = LoopEvent(tick, 0x80, note, 0)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    /** Where each track of a file ends, in ticks: the sum of every delta time up to its end-of-track. */
    private fun trackLengths(file: ByteArray): List<Int> {
        val lengths = ArrayList<Int>()
        var at = 14 // past the header chunk
        while (at < file.size) {
            assertEquals("MTrk", String(file, at, 4, Charsets.US_ASCII))
            val size = ((file[at + 4].toInt() and 0xFF) shl 24) or ((file[at + 5].toInt() and 0xFF) shl 16) or
                ((file[at + 6].toInt() and 0xFF) shl 8) or (file[at + 7].toInt() and 0xFF)
            var p = at + 8
            val end = p + size
            var tick = 0
            var ended = false
            fun varLength(): Int {
                var value = 0
                while (true) {
                    val b = file[p++].toInt() and 0xFF
                    value = (value shl 7) or (b and 0x7F)
                    if (b and 0x80 == 0) return value
                }
            }
            while (p < end) {
                tick += varLength()
                val status = file[p++].toInt() and 0xFF
                when {
                    status == 0xFF -> {
                        val type = file[p++].toInt() and 0xFF
                        val length = varLength() // read first: it moves p past the length itself
                        p += length
                        if (type == 0x2F) ended = true
                    }
                    status and 0xF0 == 0xC0 -> p += 1
                    else -> p += 2
                }
            }
            assertTrue("the track finishes with an end-of-track event", ended)
            assertEquals(end, p)
            lengths += tick
            at = end
        }
        return lengths
    }

    @Test
    fun `the file is the documented layout, byte for byte`() {
        val loop = LoopProject(tempoBpm = 120, bars = 1, snap = Snap.OFF)
            .withLayer(Layer(1, instrumentId = 19, channel = 1, events = listOf(on(480, 60, 90), off(720, 60))))
        val expected = ascii("MThd") + bytes(0, 0, 0, 6, 0, 1, 0, 2, 0x01, 0xE0) +
            // Track 1: name, 4/4, 120 beats a minute (500 000 microseconds a beat), end at 1920 ticks.
            ascii("MTrk") + bytes(0, 0, 0, 28) +
            bytes(0x00, 0xFF, 0x03, 0x04) + ascii("Test") +
            bytes(0x00, 0xFF, 0x58, 0x04, 0x04, 0x02, 0x18, 0x08) +
            bytes(0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20) +
            bytes(0x8F, 0x00, 0xFF, 0x2F, 0x00) +
            // Track 2: the layer. Church Organ is program 19; channel 1 is written as 0xC1, 0x91, 0x81.
            ascii("MTrk") + bytes(0, 0, 0, 34) +
            bytes(0x00, 0xFF, 0x03, 0x0C) + ascii("Church Organ") +
            bytes(0x00, 0xC1, 0x13) +
            bytes(0x83, 0x60, 0x91, 0x3C, 0x5A) + // 480 ticks in: middle C down
            bytes(0x81, 0x70, 0x81, 0x3C, 0x00) + // 240 ticks later: up
            bytes(0x89, 0x30, 0xFF, 0x2F, 0x00) // the remaining 1200 ticks of the bar
        assertArrayEquals(expected, LoopMidi.encode(loop, "Test"))
    }

    @Test
    fun `the app's own reader gets back tempo, instruments and notes`() {
        val loop = LoopProject(tempoBpm = 115, bars = 2, snap = Snap.OFF)
            .withLayer(Layer(1, instrumentId = 60, channel = 1, events = listOf(on(0, 60, 90), off(400, 60), on(960, 64, 70), off(1500, 64))))
            .withLayer(Layer(2, instrumentId = 117, channel = 2, events = listOf(on(240, 48, 127), off(300, 48))))
        val file = MidiFileReader.read(LoopMidi.encode(loop, "Holly 1"))
        assertNotNull(file)
        file!!
        assertEquals(1, file.format)
        assertEquals(LoopTiming.PPQ, file.ticksPerBeat)
        assertEquals(115.0, file.bpm, 0.001)
        assertEquals(listOf("Holly 1", "French Horn", "Melodic Tom"), file.tracks.map { it.name })
        assertEquals(emptyList<MidiNote>(), file.tracks[0].notes)
        assertEquals(mapOf(1 to 60), file.tracks[1].programs)
        assertEquals(mapOf(2 to 117), file.tracks[2].programs)
        assertEquals(
            listOf(MidiNote(0, 400, 60, channel = 1, velocity = 90), MidiNote(960, 540, 64, channel = 1, velocity = 70)),
            file.tracks[1].notes,
        )
        assertEquals(listOf(MidiNote(240, 60, 48, channel = 2, velocity = 127)), file.tracks[2].notes)
    }

    @Test
    fun `every track is exactly as long as the loop`() {
        for (bars in LoopProject.BAR_CHOICES) {
            val loop = LoopProject(bars = bars, snap = Snap.OFF)
                .withLayer(Layer(1, 0, channel = 1, events = listOf(on(100, 60), off(200, 60))))
                .withLayer(Layer(2, 5, channel = 2, events = listOf(on(0, 62), off(bars * 1920 - 1, 62))))
            assertEquals(List(3) { bars * 1920 }, trackLengths(LoopMidi.encode(loop)))
        }
    }

    @Test
    fun `muted, silent and empty layers are left out`() {
        val note = listOf(on(0, 60), off(100, 60))
        val loop = LoopProject(snap = Snap.OFF)
            .withLayer(Layer(1, instrumentId = 0, channel = 1, events = note, muted = true))
            .withLayer(Layer(2, instrumentId = 40, channel = 2, events = note))
            .withLayer(Layer(3, instrumentId = 56, channel = 3, events = note, volume = 0))
            .withLayer(Layer(4, instrumentId = 73, channel = 4))
        assertEquals(listOf(2), LoopMidi.audibleLayers(loop).map { it.id })
        val file = MidiFileReader.read(LoopMidi.encode(loop))!!
        assertEquals(listOf(LoopMidi.DEFAULT_TITLE, "Violin"), file.tracks.map { it.name })
    }

    @Test
    fun `a loop with nothing to hear still makes a readable file`() {
        val file = MidiFileReader.read(LoopMidi.encode(LoopProject()))!!
        assertEquals(1, file.tracks.size)
        assertEquals(emptyList<Layer>(), LoopMidi.audibleLayers(LoopProject()))
    }

    @Test
    fun `notes are written where the snap grid puts them, keeping their length`() {
        val events = listOf(on(500, 60), off(700, 60), on(1400, 62), off(1500, 62))
        val loop = LoopProject(bars = 1, snap = Snap.BEAT).withLayer(Layer(1, 0, channel = 1, events = events))
        val notes = MidiFileReader.read(LoopMidi.encode(loop))!!.tracks[1].notes
        assertEquals(listOf(480L to 200L, 1440L to 100L), notes.map { it.startTick to it.lengthTicks })
        // The recording itself is untouched: with snap off the same loop comes out as played.
        val asPlayed = MidiFileReader.read(LoopMidi.encode(loop.copy(snap = Snap.OFF)))!!.tracks[1].notes
        assertEquals(listOf(500L to 200L, 1400L to 100L), asPlayed.map { it.startTick to it.lengthTicks })
    }

    @Test
    fun `a note snapped forward past the end lands on the first beat`() {
        val loop = LoopProject(bars = 1, snap = Snap.BEAT)
            .withLayer(Layer(1, 0, channel = 1, events = listOf(on(1800, 60), off(1900, 60))))
        val note = MidiFileReader.read(LoopMidi.encode(loop))!!.tracks[1].notes.single()
        assertEquals(0L to 100L, note.startTick to note.lengthTicks)
    }

    @Test
    fun `layer volume is written into the velocities`() {
        val loop = LoopProject(snap = Snap.OFF)
            .withLayer(Layer(1, 0, channel = 1, events = listOf(on(0, 60, 100), off(100, 60)), volume = 50))
        val note = MidiFileReader.read(LoopMidi.encode(loop))!!.tracks[1].notes.single()
        assertEquals(LayerVolume.scale(100, 50), note.velocity)
        assertEquals(50, note.velocity)
    }

    @Test
    fun `a note held across the end of the loop is cut off at the end`() {
        // Pressed in the last beat, released early in the next pass.
        val loop = LoopProject(bars = 1, snap = Snap.OFF)
            .withLayer(Layer(1, 0, channel = 1, events = listOf(on(1700, 60), off(100, 60))))
        val bytes = LoopMidi.encode(loop)
        val note = MidiFileReader.read(bytes)!!.tracks[1].notes.single()
        assertEquals(1700L to 220L, note.startTick to note.lengthTicks)
        assertEquals(listOf(1920, 1920), trackLengths(bytes))
    }

    @Test
    fun `a key still held when the recording stopped gets a short note`() {
        val loop = LoopProject(bars = 1, snap = Snap.OFF).withLayer(Layer(1, 0, channel = 1, events = listOf(on(480, 60))))
        val note = MidiFileReader.read(LoopMidi.encode(loop))!!.tracks[1].notes.single()
        assertEquals(LoopNotes.UNFINISHED_LENGTH_TICKS.toLong(), note.lengthTicks)
    }

    @Test
    fun `drums go on channel 10 with no instrument change`() {
        val loop = LoopProject(snap = Snap.OFF).withLayer(
            Layer(1, Instruments.DRUM_KIT_ID, Instruments.DRUM_CHANNEL, listOf(on(0, 36), off(50, 36), on(480, 38), off(530, 38))),
        )
        val track = MidiFileReader.read(LoopMidi.encode(loop))!!.tracks[1]
        assertEquals("Drum Kit", track.name)
        assertEquals(emptyMap<Int, Int>(), track.programs)
        assertEquals(listOf(9, 9), track.notes.map { it.channel })
        assertEquals(listOf(36, 38), track.notes.map { it.pitch })
    }

    @Test
    fun `a key struck again exactly where it ends is written release first`() {
        val loop = LoopProject(bars = 1, snap = Snap.OFF)
            .withLayer(Layer(1, 0, channel = 1, events = listOf(on(0, 60), off(480, 60), on(480, 60), off(960, 60))))
        val notes = MidiFileReader.read(LoopMidi.encode(loop))!!.tracks[1].notes
        assertEquals(listOf(0L to 480L, 480L to 480L), notes.map { it.startTick to it.lengthTicks })
    }

    @Test
    fun `odd tempos are written to the nearest microsecond`() {
        for (tempo in listOf(LoopProject.MIN_TEMPO, 70, 133, LoopProject.MAX_TEMPO)) {
            val file = MidiFileReader.read(LoopMidi.encode(LoopProject(tempoBpm = tempo)))!!
            assertEquals(tempo.toDouble(), file.bpm, 0.001)
        }
    }

    @Test
    fun `a name the file format cannot spell does not break the file`() {
        val file = MidiFileReader.read(LoopMidi.encode(LoopProject(), "Zo\u00EB \u97F3"))!!
        assertEquals("Zo\u00EB ?", file.tracks[0].name)
    }
}
