package com.dhobson.lumi.loop

import com.dhobson.lumi.music.Instruments
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoopModelTest {
    private val sampleRate = 48_000

    // 120 bpm at 48 kHz: one beat is exactly 24 000 frames, one tick is 50 frames.
    // Snap is off here so that the compile tests see events exactly where they are stored.
    private val project = LoopProject(tempoBpm = 120, bars = 1, snap = Snap.OFF)

    private fun on(tick: Int, note: Int, velocity: Int = 100) = LoopEvent(tick, 0x90, note, velocity)
    private fun off(tick: Int, note: Int) = LoopEvent(tick, 0x80, note, 0)

    private fun CompiledLoop.asPairs() = frames.indices.map { frames[it] to messages[it] }

    // ---- timing ----

    @Test
    fun `tempo and bars give the loop length in frames`() {
        assertEquals(24_000.0, LoopTiming.framesPerBeat(sampleRate, 120), 0.0)
        assertEquals(96_000, LoopTiming.lengthFrames(project, sampleRate))             // 1 bar of 4 beats
        assertEquals(288_000, LoopTiming.lengthFrames(LoopProject(80, bars = 2), sampleRate)) // 8 beats of 36 000
        assertEquals(1920, project.lengthTicks)
    }

    @Test
    fun `ticks and frames convert both ways`() {
        assertEquals(24_000, LoopTiming.tickToFrame(480, 24_000.0))
        assertEquals(480, LoopTiming.frameToTick(24_000, 24_000.0))
        assertEquals(240, LoopTiming.frameToTick(12_010, 24_000.0)) // rounds to the nearest tick
        // 44.1 kHz at 100 bpm is not a whole number of frames per tick; still round-trips.
        val framesPerBeat = LoopTiming.framesPerBeat(44_100, 100)
        for (tick in listOf(0, 1, 239, 480, 1919)) {
            assertEquals(tick, LoopTiming.frameToTick(LoopTiming.tickToFrame(tick, framesPerBeat).toLong(), framesPerBeat))
        }
    }

    // ---- compiling for the native sequencer ----

    @Test
    fun `a layer compiles to its instrument at frame 0 followed by its notes on its channel`() {
        val layer = Layer(id = 1, instrumentId = 19, channel = 3, events = listOf(on(0, 60), off(480, 60)))
        val compiled = LoopCompiler.compile(project.withLayer(layer), sampleRate)
        assertEquals(
            listOf(
                0 to LoopCompiler.pack(0xC3, 19, 0),       // Church Organ on channel 4
                0 to LoopCompiler.pack(0x93, 60, 100),
                24_000 to LoopCompiler.pack(0x83, 60, 0),
            ),
            compiled.asPairs(),
        )
        assertEquals(96_000, compiled.lengthFrames)
        assertEquals(24_000.0, compiled.framesPerBeat, 0.0)
        assertEquals(4, compiled.beatsPerBar)
    }

    @Test
    fun `at the same frame a release comes before a new note`() {
        // The same key released and re-struck on the same tick, stored in the "wrong" order.
        val layer = Layer(1, instrumentId = 0, channel = 1, events = listOf(on(0, 60), on(480, 60), off(480, 60), off(960, 60)))
        val statuses = LoopCompiler.compile(project.withLayer(layer), sampleRate).messages.map { it and 0xF0 }
        assertEquals(listOf(0xC0, 0x90, 0x80, 0x90, 0x80), statuses)
    }

    @Test
    fun `events from several layers are merged in time order`() {
        val piano = Layer(1, instrumentId = 0, channel = 1, events = listOf(on(960, 60), off(1440, 60)))
        val bass = Layer(2, instrumentId = 32, channel = 2, events = listOf(on(0, 36), off(480, 36)))
        val compiled = LoopCompiler.compile(project.withLayer(piano).withLayer(bass), sampleRate)
        assertArrayEquals(intArrayOf(0, 0, 0, 24_000, 48_000, 72_000), compiled.frames)
    }

    @Test
    fun `muted layers are left out and drums get no instrument change`() {
        val muted = Layer(1, instrumentId = 0, channel = 1, events = listOf(on(0, 60), off(100, 60)), muted = true)
        val drums = Layer(2, Instruments.DRUM_KIT_ID, Instruments.DRUM_CHANNEL, listOf(on(0, 36), off(10, 36)))
        val compiled = LoopCompiler.compile(project.withLayer(muted).withLayer(drums), sampleRate)
        assertEquals(listOf(0x99, 0x89), compiled.messages.map { it and 0xFF })
    }

    @Test
    fun `events never land on or past the loop end`() {
        val layer = Layer(1, 0, 1, listOf(on(1919, 60), off(5000, 60)))
        val compiled = LoopCompiler.compile(project.withLayer(layer), sampleRate)
        assertEquals(95_999, compiled.frames.max())
    }

    @Test
    fun `an empty project still compiles to a loop of the right length`() {
        val compiled = LoopCompiler.compile(project, sampleRate)
        assertEquals(0, compiled.frames.size)
        assertEquals(96_000, compiled.lengthFrames)
    }

    // ---- channels ----

    @Test
    fun `layers get their own channel, never the live channel or the drum channel`() {
        var layers = emptyList<Layer>()
        val given = ArrayList<Int>()
        repeat(14) {
            val channel = LoopChannels.allocate(layers, instrumentId = 0)!!
            given += channel
            layers = layers + Layer(it, 0, channel)
        }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15), given)
        assertNull(LoopChannels.allocate(layers, instrumentId = 0)) // all melodic channels in use
        assertEquals(9, LoopChannels.allocate(layers, Instruments.DRUM_KIT_ID)) // drums always share 9
    }

    @Test
    fun `a deleted layer's channel is reused`() {
        val layers = listOf(Layer(1, 0, 1), Layer(3, 0, 3))
        assertEquals(2, LoopChannels.allocate(layers, instrumentId = 5))
    }

    // ---- recording a take ----

    private fun recorder(compensationFrames: Int = 0) =
        TakeRecorder(lengthTicks = 1920, lengthFrames = 96_000, framesPerBeat = 24_000.0, compensationFrames = compensationFrames)

    @Test
    fun `captured notes become events at the matching tick`() {
        val take = recorder()
        assertEquals(on(480, 60, 90), take.onCaptured(24_000, LoopCompiler.pack(0x90, 60, 90)))
        assertEquals(off(960, 60), take.onCaptured(48_000, LoopCompiler.pack(0x80, 60, 64)))
    }

    @Test
    fun `the channel played on is ignored and note-on with velocity 0 is a release`() {
        val take = recorder()
        assertEquals(on(0, 36, 100), take.onCaptured(0, LoopCompiler.pack(0x99, 36, 100)))
        assertEquals(off(240, 36), take.onCaptured(12_000, LoopCompiler.pack(0x99, 36, 0)))
    }

    @Test
    fun `output delay is subtracted, wrapping round the loop start`() {
        val take = recorder(compensationFrames = 12_000) // a quarter of a second at 48 kHz
        assertEquals(on(240, 60), take.onCaptured(24_000, LoopCompiler.pack(0x90, 60, 100)))
        // Captured just after the loop start: it was aimed at the end of the previous pass.
        assertEquals(off(1800, 60), take.onCaptured(6_000, LoopCompiler.pack(0x80, 60, 0)))
    }

    @Test
    fun `notes still held when the take ends are released there`() {
        val take = recorder()
        take.onCaptured(0, LoopCompiler.pack(0x90, 60, 100))
        take.onCaptured(100, LoopCompiler.pack(0x90, 64, 100))
        take.onCaptured(200, LoopCompiler.pack(0x80, 64, 0))
        take.onCaptured(300, LoopCompiler.pack(0x90, 67, 100))
        assertEquals(listOf(off(1440, 60), off(1440, 67)), take.closeHeld(72_000))
        assertEquals(emptyList<LoopEvent>(), take.closeHeld(72_000)) // nothing left
    }

    @Test
    fun `non-note messages are not recorded`() {
        assertNull(recorder().onCaptured(0, LoopCompiler.pack(0xB0, 64, 127)))
    }

    // ---- snap (quantise) ----

    private val length = 1920 // one bar

    @Test
    fun `snap off leaves the recording untouched`() {
        val events = listOf(on(70, 60), off(300, 60))
        assertEquals(events, LoopQuantizer.apply(events, Snap.OFF.gridTicks, length))
    }

    @Test
    fun `a note start moves to the nearest grid line and the note keeps its length`() {
        // The four slightly-late notes recorded in the emulator test, each held for 200 ticks.
        val played = listOf(70, 495, 911, 1321).flatMap { listOf(on(it, 60), off(it + 200, 60)) }
        val snapped = LoopQuantizer.apply(played, Snap.BEAT.gridTicks, length)
        assertEquals(listOf(0, 480, 960, 1440), snapped.filter { it.status == 0x90 }.map { it.tick })
        assertEquals(listOf(200, 680, 1160, 1640), snapped.filter { it.status == 0x80 }.map { it.tick })
    }

    @Test
    fun `finer grids move notes less`() {
        val played = listOf(on(170, 60), off(400, 60))
        assertEquals(listOf(on(0, 60), off(230, 60)), LoopQuantizer.apply(played, Snap.BEAT.gridTicks, length))
        assertEquals(listOf(on(240, 60), off(470, 60)), LoopQuantizer.apply(played, Snap.HALF_BEAT.gridTicks, length))
        assertEquals(listOf(on(120, 60), off(350, 60)), LoopQuantizer.apply(played, Snap.QUARTER_BEAT.gridTicks, length))
    }

    @Test
    fun `a note exactly half way rounds up, and early notes round up to the beat they were aiming for`() {
        assertEquals(480, LoopQuantizer.apply(listOf(on(240, 60)), 480, length).single().tick)
        assertEquals(480, LoopQuantizer.apply(listOf(on(430, 60)), 480, length).single().tick)
    }

    @Test
    fun `a chord played slightly spread lands together`() {
        val played = listOf(on(950, 60), on(962, 64), on(975, 67), off(1400, 60), off(1405, 64), off(1410, 67))
        val starts = LoopQuantizer.apply(played, Snap.BEAT.gridTicks, length).filter { it.status == 0x90 }
        assertEquals(listOf(960, 960, 960), starts.map { it.tick })
    }

    @Test
    fun `a note just before the loop end snaps round to the start`() {
        val played = listOf(on(1900, 60), off(100, 60)) // held across the loop boundary
        assertEquals(listOf(on(0, 60), off(120, 60)), LoopQuantizer.apply(played, Snap.BEAT.gridTicks, length))
    }

    @Test
    fun `the same key played twice keeps each start with its own end`() {
        val played = listOf(on(10, 60), off(200, 60), on(250, 60), off(400, 60))
        assertEquals(
            listOf(on(0, 60), off(190, 60), on(240, 60), off(390, 60)),
            LoopQuantizer.apply(played, Snap.HALF_BEAT.gridTicks, length),
        )
    }

    @Test
    fun `overlapping notes on different keys are paired by key, and a stray release is left alone`() {
        val played = listOf(off(5, 72), on(100, 60), on(130, 64), off(300, 64), off(500, 60))
        assertEquals(
            listOf(off(5, 72), on(0, 60), on(240, 64), off(410, 64), off(400, 60)),
            LoopQuantizer.apply(played, Snap.HALF_BEAT.gridTicks, length),
        )
    }

    @Test
    fun `snap is applied when the loop is compiled, without changing the stored layer`() {
        val layer = Layer(1, instrumentId = 0, channel = 1, events = listOf(on(70, 60), off(270, 60)))
        val loose = LoopCompiler.compile(project.withLayer(layer), sampleRate)
        val tidy = LoopCompiler.compile(project.withLayer(layer).copy(snap = Snap.BEAT), sampleRate)
        assertArrayEquals(intArrayOf(0, 3_500, 13_500), loose.frames) // instrument, then 70 and 270 ticks
        assertArrayEquals(intArrayOf(0, 0, 10_000), tidy.frames)      // start on the beat, same length
        assertEquals(listOf(on(70, 60), off(270, 60)), layer.events)
    }

    @Test
    fun `a new loop snaps to the beat unless told otherwise`() {
        assertEquals(Snap.BEAT, LoopProject().snap)
    }

    // ---- notes for the layer timeline ----

    @Test
    fun `spans give each note's start, length and pitch`() {
        val played = listOf(on(0, 60), off(240, 60), on(480, 64), off(1000, 64))
        assertEquals(
            listOf(NoteSpan(0, 240, 60), NoteSpan(480, 520, 64)),
            LoopNotes.spans(played, Snap.OFF.gridTicks, length),
        )
    }

    @Test
    fun `spans show notes where they are heard, so snapped notes sit on the grid`() {
        val played = listOf(70, 495, 911, 1321).flatMap { listOf(on(it, 60), off(it + 200, 60)) }
        assertEquals(listOf(70, 495, 911, 1321), LoopNotes.spans(played, Snap.OFF.gridTicks, length).map { it.startTick })
        val snapped = LoopNotes.spans(played, Snap.BEAT.gridTicks, length)
        assertEquals(listOf(0, 480, 960, 1440), snapped.map { it.startTick })
        assertEquals(listOf(200, 200, 200, 200), snapped.map { it.lengthTicks })
    }

    @Test
    fun `a note held across the loop end has its full length, and a key still held gets a short one`() {
        val played = listOf(on(1800, 60), off(100, 60), on(900, 64))
        assertEquals(
            listOf(NoteSpan(1800, 220, 60), NoteSpan(900, LoopNotes.UNFINISHED_LENGTH_TICKS, 64)),
            LoopNotes.spans(played, Snap.OFF.gridTicks, length),
        )
    }

    @Test
    fun `overlapping and repeated keys keep their own lengths`() {
        val played = listOf(on(0, 60), on(100, 64), off(150, 60), on(200, 60), off(300, 64), off(500, 60))
        assertEquals(
            listOf(NoteSpan(0, 150, 60), NoteSpan(100, 200, 64), NoteSpan(200, 300, 60)),
            LoopNotes.spans(played, Snap.OFF.gridTicks, length),
        )
    }

    @Test
    fun `releases and other events on their own make no spans`() {
        assertEquals(emptyList<NoteSpan>(), LoopNotes.spans(listOf(off(10, 60)), Snap.BEAT.gridTicks, length))
        assertEquals(emptyList<NoteSpan>(), LoopNotes.spans(emptyList(), Snap.BEAT.gridTicks, length))
    }

    // ---- position display ----

    @Test
    fun `position is shown as count-in beats, then bar and beat`() {
        val fpb = 24_000.0
        assertEquals(LoopPosition(4, 0, 0), LoopPosition.at(-96_000, fpb, 4))
        assertEquals(LoopPosition(4, 0, 0), LoopPosition.at(-72_001, fpb, 4))
        assertEquals(LoopPosition(3, 0, 0), LoopPosition.at(-72_000, fpb, 4))
        assertEquals(LoopPosition(1, 0, 0), LoopPosition.at(-1, fpb, 4))
        assertEquals(LoopPosition(0, 1, 1), LoopPosition.at(0, fpb, 4))
        assertEquals(LoopPosition(0, 1, 4), LoopPosition.at(95_999, fpb, 4))
        assertEquals(LoopPosition(0, 2, 1), LoopPosition.at(96_000, fpb, 4))
        assertEquals(LoopPosition(0, 2, 3), LoopPosition.at(150_000, fpb, 4))
    }

    // ---- project edits ----

    @Test
    fun `layers can be added, changed and removed`() {
        val a = Layer(1, 0, 1, listOf(on(0, 60), off(10, 60), on(20, 62), off(30, 62)))
        val b = Layer(2, 40, 2)
        var p = project.withLayer(a).withLayer(b)
        assertEquals(2, a.noteCount)
        p = p.updateLayer(1) { it.copy(muted = true) }
        assertEquals(listOf(true, false), p.layers.map { it.muted })
        p = p.withoutLayer(1)
        assertEquals(listOf(2), p.layers.map { it.id })
    }
}
