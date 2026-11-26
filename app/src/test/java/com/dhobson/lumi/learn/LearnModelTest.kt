package com.dhobson.lumi.learn

import com.dhobson.lumi.music.KeyboardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnModelTest {
    private fun song(text: String, bpm: Int = 100) = Song("test", "Test", "Nobody", bpm, SongText.parse(text)!!)

    // ---- writing songs down ----

    @Test
    fun `note names become MIDI note numbers`() {
        assertEquals(60, SongText.pitchOf("C4"))
        assertEquals(69, SongText.pitchOf("A4"))
        assertEquals(61, SongText.pitchOf("C#4"))
        assertEquals(70, SongText.pitchOf("Bb4"))
        assertEquals(55, SongText.pitchOf("G3"))
        assertEquals(60, SongText.pitchOf("c4"))
        assertNull(SongText.pitchOf("H4"))
        assertNull(SongText.pitchOf("C"))
        assertNull(SongText.pitchOf("C#"))
        assertNull(SongText.pitchOf("C99"))
    }

    @Test
    fun `a melody is read as notes one after another, with rests and bar lines`() {
        assertEquals(
            listOf(SongNote(0, 480, 60), SongNote(480, 240, 62), SongNote(1200, 960, 64), SongNote(2160, 720, 66)),
            SongText.parse("C4:1 D4:0.5 | -:1 E4:2\n F#4:1.5"),
        )
    }

    @Test
    fun `text that is not a melody is refused`() {
        assertNull(SongText.parse("C4"))
        assertNull(SongText.parse("C4:x"))
        assertNull(SongText.parse("X4:1"))
        assertNull(SongText.parse("C4:0"))
        assertNull(SongText.parse("C4:-1"))
        assertEquals(emptyList<SongNote>(), SongText.parse("  "))
    }

    // ---- the built-in tunes ----

    @Test
    fun `every built-in tune fits on the keyboard at each octave setting, and ids are unique`() {
        assertTrue(Songs.all.size >= 6)
        assertEquals(Songs.all.size, Songs.all.map { it.id }.toSet().size)
        for (song in Songs.all) {
            assertTrue("${song.id} has no notes", song.notes.isNotEmpty())
            assertTrue("${song.id} is not sorted", song.notes.zipWithNext().all { (a, b) -> a.startTick <= b.startTick })
            for (base in listOf(36, 48, 60, 72)) {
                val fitted = SongFit.toWindow(song, base)
                assertNotNull("${song.id} does not fit at $base", fitted)
                assertTrue(fitted!!.notes.all { it.pitch in base until base + SongFit.KEYS })
            }
        }
    }

    @Test
    fun `spot checks of the built-in tunes`() {
        val twinkle = Songs.byId("twinkle")!!
        assertEquals(42, twinkle.notes.size)
        assertEquals(listOf(60, 60, 67, 67, 69, 69, 67), twinkle.notes.take(7).map { it.pitch })
        assertEquals(48 * Song.PPQ, twinkle.endTick) // twelve bars of four beats
        val elise = Songs.byId("fur-elise")!!
        assertEquals(listOf(76, 75, 76, 75, 76, 71, 74, 72, 69), elise.notes.take(9).map { it.pitch })
        assertEquals(16 * Song.PPQ, Songs.byId("hot-cross-buns")!!.endTick)
        assertEquals(32 * Song.PPQ, Songs.byId("ode-to-joy")!!.endTick)
    }

    @Test
    fun `the tunes taken from published notation are as the converter left them`() {
        // Note count and length in beats, as reported by tools/tunes_from_sources.py. A change
        // here means a tune was edited by hand, or the converter's output was pasted wrongly.
        val expected = mapOf(
            "au-clair-lune" to (44 to 64.0), "london-bridge" to (24 to 16.0), "lightly-row" to (49 to 32.0),
            "row-boat" to (27 to 24.0), "when-saints" to (31 to 32.0), "joy-to-world" to (57 to 40.0),
            "silent-night" to (46 to 35.5), "drunken-sailor" to (63 to 32.0), "korobeiniki" to (51 to 48.0),
            "minuet-g" to (32 to 24.0), "mountain-king" to (52 to 31.0),
        )
        for ((id, shape) in expected) {
            val song = Songs.byId(id)
            assertNotNull("$id is missing", song)
            assertEquals("$id: notes", shape.first, song!!.notes.size)
            assertEquals("$id: beats", shape.second, song.endTick.toDouble() / Song.PPQ, 0.0)
            // One note at a time: none starts before the one before it has ended.
            assertTrue("$id overlaps", song.notes.zipWithNext().all { (a, b) -> a.endTick <= b.startTick })
        }
        assertEquals(8 + expected.size, Songs.all.size)
    }

    @Test
    fun `spot checks of the tunes taken from published notation`() {
        fun opening(id: String, count: Int) = Songs.byId(id)!!.notes.take(count).map { it.pitch }
        assertEquals(listOf(67, 69, 67, 65, 64, 65, 67), opening("london-bridge", 7))        // G A G F E F G
        assertEquals(listOf(60, 60, 60, 62, 64), opening("row-boat", 5))                      // C C C D E
        assertEquals(listOf(72, 71, 69, 67, 65, 64, 62, 60), opening("joy-to-world", 8))      // down the scale
        assertEquals(listOf(74, 67, 69, 71, 72, 74, 67, 67), opening("minuet-g", 8))          // D G A B C D G G
        assertEquals(listOf(59, 61, 62, 64, 66, 62, 66), opening("mountain-king", 7))         // B C# D E F# D F#
        // "When the Saints" starts after a half-beat rest.
        assertEquals(Song.PPQ / 2, Songs.byId("when-saints")!!.notes.first().startTick)
        // The easiest tune is still the one a new player is offered first.
        assertEquals("hot-cross-buns", Songs.all.first().id)
    }

    // ---- fitting a song to the keyboard ----

    @Test
    fun `a song that fits is left at its written pitch, otherwise it moves by as few octaves as it takes`() {
        val low = song("G3:1 C4:1 A4:1") // 55..69
        assertEquals(listOf(67, 72, 81), SongFit.toWindow(low, 60)!!.notes.map { it.pitch })  // up an octave
        assertEquals(listOf(55, 60, 69), SongFit.toWindow(low, 48)!!.notes.map { it.pitch })  // already fits
        assertEquals(listOf(43, 48, 57), SongFit.toWindow(low, 36)!!.notes.map { it.pitch })  // down an octave
        val high = song("E5:1 C6:1")     // 76..84
        assertEquals(listOf(64, 72), SongFit.toWindow(high, 60)!!.notes.map { it.pitch })  // one octave down
        assertEquals(listOf(76, 84), SongFit.toWindow(high, 72)!!.notes.map { it.pitch })  // fits as written
        val upper = song("C5:1 G5:1")    // 72..79: fits a C4 keyboard as written, so it is not moved down
        assertEquals(listOf(72, 79), SongFit.toWindow(upper, 60)!!.notes.map { it.pitch })
        assertEquals(listOf(48, 55), SongFit.toWindow(upper, 36)!!.notes.map { it.pitch }) // two octaves down
        assertEquals(listOf(96, 103), SongFit.toWindow(upper, 96)!!.notes.map { it.pitch }) // two octaves up
    }

    @Test
    fun `a song wider than the keyboard, or that octaves cannot place, does not fit`() {
        assertNull(SongFit.toWindow(song("C3:1 C5:1"), 60))                  // 25 keys
        assertNotNull(SongFit.toWindow(song("C3:1 B4:1"), 60))               // exactly 24
        assertNull(SongFit.toWindow(song("D3:1 C#5:1"), 60))                 // 24 keys, but starts on D
        assertEquals(emptyList<SongNote>(), SongFit.toWindow(song(" "), 60)!!.notes)
    }

    // ---- wait mode ----

    @Test
    fun `the song runs up to the next note and then waits there`() {
        val engine = LearnEngine(song("C4:1 D4:1"))
        assertEquals(-960.0, engine.position, 0.0) // two beats of lead-in
        assertFalse(engine.waiting)
        assertEquals(setOf(60), engine.targets)

        engine.advance(500.0)
        assertEquals(-460.0, engine.position, 0.0)
        engine.advance(5000.0)
        assertEquals(0.0, engine.position, 0.0) // held at the first note
        assertTrue(engine.waiting)
        engine.advance(5000.0)
        assertEquals(0.0, engine.position, 0.0)
    }

    @Test
    fun `the right key lets the song go on to the next note`() {
        val engine = LearnEngine(song("C4:1 D4:1"))
        engine.advance(960.0)
        assertTrue(engine.keyDown(60))
        assertFalse(engine.waiting)
        assertEquals(setOf(62), engine.targets)
        assertEquals(480, engine.targetTick)

        engine.advance(10_000.0)
        assertEquals(480.0, engine.position, 0.0)
        assertFalse(engine.finished)
        assertTrue(engine.keyDown(62))
        assertEquals(emptySet<Int>(), engine.targets)
        assertNull(engine.targetTick)

        engine.advance(100.0)
        assertFalse(engine.finished) // the last note is still sounding
        engine.advance(10_000.0)
        assertEquals(960.0, engine.position, 0.0)
        assertTrue(engine.finished)
    }

    @Test
    fun `a wrong key is counted and the song keeps waiting`() {
        val engine = LearnEngine(song("C4:1 D4:1 E4:1"))
        engine.advance(960.0)
        assertFalse(engine.keyDown(61))
        assertFalse(engine.keyDown(62)) // the next note, but not yet
        assertTrue(engine.waiting)
        assertEquals(2, engine.wrongPresses)
        assertTrue(engine.keyDown(60))
        assertTrue(engine.keyDown(62))
        assertTrue(engine.keyDown(64))
        assertEquals(2, engine.wrongPresses)
        assertEquals(2, engine.cleanGroups) // the first note took three tries
    }

    @Test
    fun `a key pressed before its note arrives is accepted and the song catches up`() {
        val engine = LearnEngine(song("C4:1 D4:1"))
        assertTrue(engine.keyDown(60)) // during the lead-in
        assertEquals(0.0, engine.position, 0.0)
        assertTrue(engine.keyDown(62))
        assertEquals(480.0, engine.position, 0.0)
        assertEquals(0, engine.wrongPresses)
        assertEquals(2, engine.cleanGroups)
    }

    @Test
    fun `notes that start together must all be pressed, in any order`() {
        val chord = Song("c", "Chord", "Nobody", 100, listOf(SongNote(0, 480, 60), SongNote(0, 480, 64), SongNote(480, 480, 67)))
        val engine = LearnEngine(chord)
        assertEquals(2, engine.groups.size)
        engine.advance(960.0)
        assertTrue(engine.keyDown(64))
        assertTrue(engine.waiting)
        assertEquals(setOf(60), engine.targets)
        assertFalse(engine.keyDown(64)) // already pressed: counts as a slip
        assertTrue(engine.keyDown(60))
        assertEquals(setOf(67), engine.targets)
        assertEquals(0, engine.cleanGroups)
    }

    @Test
    fun `keys pressed after the last note do nothing`() {
        val engine = LearnEngine(song("C4:1"))
        engine.keyDown(60)
        assertFalse(engine.keyDown(60))
        assertEquals(0, engine.wrongPresses)
    }

    @Test
    fun `a whole built-in tune can be played through`() {
        val twinkle = Songs.byId("twinkle")!!
        val engine = LearnEngine(twinkle)
        var presses = 0
        while (!engine.finished && presses < 1000) {
            engine.advance(120.0)
            if (engine.waiting) {
                engine.keyDown(engine.targets.first())
                presses++
            }
        }
        assertTrue(engine.finished)
        assertEquals(42, presses)
        assertEquals(42, engine.cleanGroups)
    }

    // ---- "in time" mode ----

    // 100 bpm at 480 ticks a beat is 800 ticks a second, so 1 tick = 1.25 ms:
    // the perfect window (110 ms) is 88 ticks and the good window (260 ms) is 208 ticks.
    private val tps = 800.0
    private fun timed(text: String, compensationTicks: Double = 0.0) = TimedEngine(song(text), tps, compensationTicks)
    private fun TimedEngine.goTo(tick: Double) = advance(tick - position)

    @Test
    fun `the song does not wait, and a note nobody plays is missed once its window has passed`() {
        val engine = timed("C4:1 D4:1")
        assertFalse(engine.waiting)
        assertFalse(engine.goTo(200.0))             // still inside the first note's window
        assertEquals(listOf(Mark.PENDING, Mark.PENDING), engine.marks())
        assertEquals(setOf(60), engine.targets)
        assertTrue(engine.goTo(209.0))              // 208 ticks after it: gone
        assertEquals(listOf(Mark.MISSED, Mark.PENDING), engine.marks())
        assertEquals(setOf(62), engine.targets)
        assertEquals(480, engine.targetTick)
        engine.goTo(959.0)
        assertFalse(engine.finished)                // the second note is missed, but the song has not ended
        engine.goTo(960.0)
        assertTrue(engine.finished)
        assertEquals(Tally(total = 2, done = 2, perfect = 0, good = 0, missed = 2, wrong = 0, scorePercent = 0), engine.tally())
    }

    @Test
    fun `presses are perfect, good or wrong by how close they are, early or late`() {
        val engine = timed("C4:1 D4:1 E4:1 F4:1")
        engine.goTo(-88.0);  assertTrue(engine.keyDown(60));  assertEquals(Judgement.PERFECT, engine.lastJudgement) // 110 ms early
        engine.goTo(569.0);  assertTrue(engine.keyDown(62));  assertEquals(Judgement.GOOD, engine.lastJudgement)    // 89 ticks late
        engine.goTo(752.0);  assertTrue(engine.keyDown(64));  assertEquals(Judgement.GOOD, engine.lastJudgement)    // 208 ticks early
        engine.goTo(1200.0); assertFalse(engine.keyDown(65)); assertEquals(Judgement.WRONG, engine.lastJudgement)   // 240 ticks early: too soon
        engine.goTo(1440.0); assertTrue(engine.keyDown(65));  assertEquals(Judgement.PERFECT, engine.lastJudgement)
        assertEquals(listOf(Mark.HIT, Mark.HIT, Mark.HIT, Mark.HIT), engine.marks())
        // (2 x 100 + 2 x 60 - 1 x 25) / 4 = 73.75
        assertEquals(Tally(4, 4, perfect = 2, good = 2, missed = 0, wrong = 1, scorePercent = 74), engine.tally())
        assertEquals(2, engine.tally().stars)
    }

    @Test
    fun `the right key at the wrong time and the wrong key at the right time both count as wrong`() {
        val engine = timed("C4:1 D4:1")
        engine.goTo(0.0)
        assertFalse(engine.keyDown(62))   // D is not due for another beat
        assertFalse(engine.keyDown(61))   // not in the song at all
        assertTrue(engine.keyDown(60))
        assertFalse(engine.keyDown(60))   // already played
        assertEquals(3, engine.tally().wrong)
        assertEquals(setOf(62), engine.targets)
    }

    @Test
    fun `repeated notes are matched to the nearest one, and each can only be played once`() {
        val engine = timed("C4:0.25 C4:0.25 C4:0.25") // 120 ticks apart: windows overlap
        engine.goTo(125.0)
        assertTrue(engine.keyDown(60))                 // nearest is the second, at 120
        assertEquals(listOf(Mark.PENDING, Mark.HIT, Mark.PENDING), engine.marks())
        assertTrue(engine.keyDown(60))                 // now the first (125 away) and third (115 away): the third
        assertEquals(listOf(Mark.PENDING, Mark.HIT, Mark.HIT), engine.marks())
        assertTrue(engine.keyDown(60))
        assertFalse(engine.keyDown(60))
        assertEquals(listOf(Mark.HIT, Mark.HIT, Mark.HIT), engine.marks())
    }

    @Test
    fun `chords - every note is judged on its own and the lit keys are what is left of the chord`() {
        val chord = Song("c", "Chord", "", 100, listOf(SongNote(0, 480, 60), SongNote(0, 480, 64), SongNote(480, 480, 67)))
        val engine = TimedEngine(chord, tps)
        engine.goTo(0.0)
        assertEquals(setOf(60, 64), engine.targets)
        assertTrue(engine.keyDown(64))
        assertEquals(setOf(60), engine.targets)
        assertEquals(0, engine.targetTick)
        engine.goTo(300.0)                             // the C was never played
        assertEquals(listOf(Mark.MISSED, Mark.HIT, Mark.PENDING), engine.marks())
        assertEquals(setOf(67), engine.targets)
    }

    @Test
    fun `with sound delay allowed for, presses are judged against where the song was heard to be`() {
        // The player hears the accompaniment 80 ticks (100 ms) late and plays in time with it.
        val engine = timed("C4:1 D4:1", compensationTicks = 80.0)
        engine.goTo(80.0)
        assertTrue(engine.keyDown(60))
        assertEquals(Judgement.PERFECT, engine.lastJudgement)   // without the allowance this would be 100 ms late
        engine.goTo(480.0 + 80 + 208)
        assertEquals(Mark.PENDING, engine.marks()[1])           // still inside the window, as heard
        engine.goTo(480.0 + 80 + 209)
        assertEquals(Mark.MISSED, engine.marks()[1])
    }

    @Test
    fun `the windows are the same length in real time at any speed`() {
        val slow = TimedEngine(song("C4:1"), ticksPerSecond = 400.0) // half speed: 104 ticks is 260 ms
        slow.advance(960.0 + 104)
        assertTrue(slow.keyDown(60))
        assertEquals(Judgement.GOOD, slow.lastJudgement)
        val slow2 = TimedEngine(song("C4:1"), ticksPerSecond = 400.0)
        slow2.advance(960.0 + 105)
        assertFalse(slow2.keyDown(60))
    }

    @Test
    fun `a whole tune played exactly in time scores 100 with three stars`() {
        val twinkle = Songs.byId("twinkle")!!
        val engine = TimedEngine(twinkle, tps)
        for (note in twinkle.notes) {
            engine.goTo(note.startTick.toDouble())
            assertTrue(engine.keyDown(note.pitch))
        }
        engine.advance(10_000.0)
        assertTrue(engine.finished)
        assertEquals(Tally(42, 42, perfect = 42, good = 0, missed = 0, wrong = 0, scorePercent = 100), engine.tally())
        assertEquals(3, engine.tally().stars)
    }

    @Test
    fun `scores and stars`() {
        assertEquals(100, Scoring.timed(total = 10, perfect = 10, good = 0, wrong = 0))
        assertEquals(60, Scoring.timed(10, perfect = 0, good = 10, wrong = 0))
        assertEquals(0, Scoring.timed(10, perfect = 1, good = 0, wrong = 20))   // never below zero
        assertEquals(0, Scoring.timed(0, 0, 0, 0))
        assertEquals(94, Scoring.wait(total = 17, firstTime = 16))
        assertEquals(0, Scoring.wait(0, 0))
        fun stars(score: Int) = Tally(1, 1, 0, 0, 0, 0, score).stars
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3), listOf(39, 40, 69, 70, 89, 90, 100).map(::stars))
    }

    @Test
    fun `wait mode reports its progress in the same terms`() {
        val engine = LearnEngine(song("C4:1 D4:1 E4:1"))
        assertEquals(listOf(Mark.PENDING, Mark.PENDING, Mark.PENDING), engine.marks())
        engine.keyDown(61)
        engine.keyDown(60)
        engine.keyDown(62)
        assertEquals(listOf(Mark.HIT, Mark.HIT, Mark.PENDING), engine.marks())
        // Three groups, two done: one right first time, one after a slip.
        assertEquals(Tally(3, 2, perfect = 1, good = 1, missed = 0, wrong = 1, scorePercent = 33), engine.tally())
        assertFalse(engine.advance(100.0)) // wait mode never changes what is wanted on its own
    }

    // ---- the countdown before a song ----

    @Test
    fun `the countdown shows 3, 2, 1 for a second each and then ends`() {
        val second = 1_000_000_000L
        val countdown = Countdown(3)
        assertEquals(3, countdown.secondsLeft)
        assertTrue(countdown.running)
        assertEquals(0, countdown.advance(second - 1))
        assertEquals(3, countdown.secondsLeft) // still inside the first second
        countdown.advance(1)
        assertEquals(2, countdown.secondsLeft)
        countdown.advance(second)
        assertEquals(1, countdown.secondsLeft)
        countdown.advance(second - 1)
        assertEquals(1, countdown.secondsLeft)
        assertTrue(countdown.running)
        countdown.advance(1)
        assertEquals(0, countdown.secondsLeft)
        assertFalse(countdown.running)
    }

    @Test
    fun `time left over when the countdown ends is handed on to the song`() {
        val countdown = Countdown(1)
        assertEquals(0, countdown.advance(900_000_000))
        assertEquals(50_000_000, countdown.advance(150_000_000)) // 100 ms finished it, 50 ms to spare
        assertEquals(16_000_000, countdown.advance(16_000_000))  // over: everything passes through
        assertEquals(0, countdown.advance(-5))
        assertFalse(Countdown(0).running)
    }

    // ---- lining notes up with keys ----

    @Test
    fun `key spans match the keyboard's own layout`() {
        val base = 60
        assertEquals(0f to 1f / 14, KeyboardLayout.keySpan(60, base))         // first white key
        assertEquals(13f / 14 to 1f / 14, KeyboardLayout.keySpan(83, base))   // last white key
        assertNull(KeyboardLayout.keySpan(59, base))
        assertNull(KeyboardLayout.keySpan(84, base))
        // Every key's centre, fed back through the touch layout, is that key.
        for (note in 60..83) {
            val (left, width) = KeyboardLayout.keySpan(note, base)!!
            val x = (left + width / 2) * 1400f
            val isBlack = (note - base) % 12 in setOf(1, 3, 6, 8, 10)
            assertEquals(note, KeyboardLayout.noteAt(x, if (isBlack) 10f else 190f, 1400f, 200f, base))
        }
        // A black key straddles the boundary between its neighbours.
        val (left, width) = KeyboardLayout.keySpan(61, base)!!
        assertEquals(1f / 14, left + width / 2, 1e-6f)
    }
}
