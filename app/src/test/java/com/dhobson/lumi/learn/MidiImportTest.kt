package com.dhobson.lumi.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The files under src/test/resources/midi were written by the mido library
 * (tools/make_midi_fixtures.py), not by this app, so these tests check the reader
 * against an independent writer. The expected notes are the ones that script asks for.
 */
class MidiImportTest {
    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/midi/$name")) { "missing test file $name" }.use { it.readBytes() }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun MidiTrack.simple() = notes.map { listOf(it.startTick.toInt(), it.lengthTicks.toInt(), it.pitch, it.channel) }

    // ---- reading files written by mido ----

    @Test
    fun `reads a multi-track file - tempo, names, instruments and notes`() {
        val file = MidiFileReader.read(resource("format1.mid"))!!
        assertEquals(1, file.format)
        assertEquals(96, file.ticksPerBeat)
        assertEquals(110.0, file.bpm, 0.01)
        assertEquals(listOf("Test Song", "Melody", "Bass", "Kit"), file.tracks.map { it.name })
        assertEquals(emptyList<List<Int>>(), file.tracks[0].simple())
        assertEquals(
            listOf(listOf(0, 96, 72, 0), listOf(96, 96, 74, 0), listOf(192, 96, 76, 0), listOf(384, 192, 79, 0)),
            file.tracks[1].simple(),
        )
        assertEquals(mapOf(0 to 73), file.tracks[1].programs)
        assertEquals(
            listOf(listOf(0, 192, 36, 1), listOf(192, 192, 43, 1), listOf(384, 192, 36, 1)),
            file.tracks[2].simple(),
        )
        assertEquals(listOf(0, 96, 192, 288), file.tracks[3].notes.map { it.startTick.toInt() })
        assertTrue(file.tracks[3].notes.all { it.channel == 9 && it.lengthTicks == 24L })
    }

    @Test
    fun `reads a single-track file - chords, overlaps, velocity-0 note-offs, two channels`() {
        val file = MidiFileReader.read(resource("format0.mid"))!!
        assertEquals(0, file.format)
        assertEquals(480, file.ticksPerBeat)
        assertEquals(120.0, file.bpm, 0.0) // no tempo in the file: the format's default
        assertEquals(
            listOf(
                listOf(0, 960, 48, 1),
                listOf(0, 480, 60, 0), listOf(0, 480, 64, 0), listOf(0, 480, 67, 0),
                listOf(480, 480, 64, 0),
                listOf(720, 480, 67, 0),
            ),
            file.tracks.single().simple(),
        )
    }

    // ---- hand-made edge cases ----

    private val header = intArrayOf(0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xE0) // format 0, 480 ppq

    private fun fileWithTrack(vararg track: Int): ByteArray =
        bytes(*header, 0x4D, 0x54, 0x72, 0x6B, 0, 0, 0, track.size, *track)

    @Test
    fun `running status, long delta times and a note left sounding`() {
        val file = MidiFileReader.read(
            fileWithTrack(
                0x00, 0x90, 60, 100,        // C4 on
                0x83, 0x60, 60, 0,          // 480 ticks later (two-byte delta), running status: C4 off
                0x00, 62, 100,              // running status again: D4 on, never released
                0x83, 0x60, 0xFF, 0x2F, 0x00, // end of track 480 ticks later
            ),
        )!!
        assertEquals(listOf(listOf(0, 480, 60, 0), listOf(480, 480, 62, 0)), file.tracks.single().simple())
    }

    @Test
    fun `the same key struck twice before release gives two notes`() {
        val file = MidiFileReader.read(
            fileWithTrack(
                0x00, 0x90, 60, 100,
                0x60, 0x90, 60, 100,   // again at 96
                0x60, 0x80, 60, 0,     // first ends at 192
                0x60, 0x80, 60, 0,     // second ends at 288
                0x00, 0xFF, 0x2F, 0x00,
            ),
        )!!
        assertEquals(listOf(listOf(0, 192, 60, 0), listOf(96, 192, 60, 0)), file.tracks.single().simple())
    }

    @Test
    fun `other events are stepped over - controllers, program, pitch bend, sysex, lyrics`() {
        val file = MidiFileReader.read(
            fileWithTrack(
                0x00, 0xB0, 7, 100,                 // volume controller
                0x00, 0xC0, 19,                     // program change (one data byte)
                0x00, 0xE0, 0, 64,                  // pitch bend
                0x00, 0xF0, 3, 0x7E, 0x7F, 0xF7,    // system exclusive, length 3
                0x00, 0xFF, 0x05, 2, 0x4C, 0x61,    // lyric "La"
                0x00, 0x90, 60, 100,
                0x60, 0x80, 60, 0,
                0x00, 0xFF, 0x2F, 0x00,
            ),
        )!!
        assertEquals(listOf(listOf(0, 96, 60, 0)), file.tracks.single().simple())
        assertEquals(mapOf(0 to 19), file.tracks.single().programs)
    }

    @Test
    fun `anything that is not a readable MIDI file is refused`() {
        assertNull(MidiFileReader.read(ByteArray(0)))
        assertNull(MidiFileReader.read("hello, not midi".toByteArray()))
        val good = resource("format1.mid")
        assertNull(MidiFileReader.read(good.copyOf(good.size - 20)))  // cut short
        assertNull(MidiFileReader.read(good.copyOf(14)))              // header only, no tracks
        val timecode = resource("format0.mid").also { it[12] = 0xE7.toByte() } // time-code timing
        assertNull(MidiFileReader.read(timecode))
        val format2 = resource("format0.mid").also { it[9] = 2 }
        assertNull(MidiFileReader.read(format2))
        assertNull(MidiFileReader.read(fileWithTrack(0x00, 60, 100))) // data with no status before it
    }

    // ---- choosing a part ----

    @Test
    fun `parts are named from the file and the tune is guessed`() {
        val parts = MidiImport.parts(MidiFileReader.read(resource("format1.mid"))!!)
        assertEquals(listOf("Melody (Flute)", "Bass (Acoustic Bass)", "Kit (Drums)"), parts.map { it.name })
        assertEquals(listOf(false, false, true), parts.map { it.isDrums })
        assertEquals(listOf(4, 3, 4), parts.map { it.noteCount })
        assertEquals(72 to 79, parts[0].lowest to parts[0].highest)
        assertEquals(0, MidiImport.likelyMelody(parts))
    }

    @Test
    fun `a single track with two channels becomes two parts`() {
        val parts = MidiImport.parts(MidiFileReader.read(resource("format0.mid"))!!)
        assertEquals(listOf("Track 1, channel 1", "Track 1, channel 2"), parts.map { it.name })
        assertEquals(listOf(5, 1), parts.map { it.noteCount })
        assertEquals(0, MidiImport.likelyMelody(parts)) // the one-note bass is too small to be the tune
        assertNull(MidiImport.likelyMelody(emptyList()))
    }

    // ---- making a song ----

    @Test
    fun `a part becomes a song at the app's own resolution, with the file's tempo`() {
        val file = MidiFileReader.read(resource("format1.mid"))!!
        val result = MidiImport.toSong(file, MidiImport.parts(file), 0, topNotesOnly = true, id = "import:x", title = "X")
        assertEquals(110, result.song.bpm)
        assertEquals("Imported", result.song.credit)
        assertEquals(
            listOf(SongNote(0, 480, 72), SongNote(480, 480, 74), SongNote(960, 480, 76), SongNote(1920, 960, 79)),
            result.song.notes,
        )
        assertEquals(0, result.movedByOctave)
        assertEquals(0, result.leftOut)
        assertNotNull(SongFit.toWindow(result.song, 60))
    }

    @Test
    fun `top notes only turns chords into one line, otherwise chords are kept`() {
        val file = MidiFileReader.read(resource("format0.mid"))!!
        val parts = MidiImport.parts(file)
        val line = MidiImport.toSong(file, parts, 0, topNotesOnly = true, id = "i", title = "T")
        assertEquals(listOf(SongNote(0, 480, 67), SongNote(480, 480, 64), SongNote(720, 480, 67)), line.song.notes)
        assertEquals(2, line.leftOut)
        val chords = MidiImport.toSong(file, parts, 0, topNotesOnly = false, id = "i", title = "T")
        assertEquals(5, chords.song.notes.size)
        assertEquals(0, chords.leftOut)
        assertEquals(3, LearnEngine(chords.song).groups.first().pitches.size)
    }

    @Test
    fun `a part wider than two octaves is folded onto the span holding most of it`() {
        val file = MidiFileReader.read(resource("wide.mid"))!!
        val result = MidiImport.toSong(file, MidiImport.parts(file), 0, topNotesOnly = true, id = "i", title = "Wide")
        assertEquals(90, result.song.bpm)
        // Written: 48 55 60 64 67 72 76 79 84 60. C4 to B5 holds seven of them; 48, 55 and 84 move.
        assertEquals(listOf(60, 67, 60, 64, 67, 72, 76, 79, 72, 60), result.song.notes.map { it.pitch })
        assertEquals(3, result.movedByOctave)
        assertEquals((0 until 10).map { it * 480 }, result.song.notes.map { it.startTick })
        for (base in listOf(36, 48, 60, 72)) assertNotNull(SongFit.toWindow(result.song, base))
    }

    @Test
    fun `notes that start almost together are treated as together, and leading silence is dropped`() {
        val file = MidiFile(
            format = 1, ticksPerBeat = 480, bpm = 100.0,
            tracks = listOf(
                MidiTrack(
                    null,
                    listOf(MidiNote(960, 480, 60, 0), MidiNote(975, 480, 64, 0), MidiNote(1440, 2, 67, 0)),
                    emptyMap(),
                ),
            ),
        )
        val result = MidiImport.toSong(file, MidiImport.parts(file), 0, topNotesOnly = false, id = "i", title = "T")
        assertEquals(listOf(0, 0, 480), result.song.notes.map { it.startTick })
        assertTrue(result.song.notes.all { it.lengthTicks >= 60 }) // a 2-tick note is made long enough to see
        assertEquals(2, LearnEngine(result.song).groups.size)
    }

    @Test
    fun `folding merges notes that land on the same key at the same time`() {
        val (notes, moved) = MidiImport.foldOntoTwoOctaves(
            listOf(SongNote(0, 480, 60), SongNote(0, 240, 96), SongNote(480, 480, 62), SongNote(960, 480, 64)),
        )
        // C7 (96) folds down two octaves onto C5 (72)... which is a different key from C4, so both stay.
        assertEquals(listOf(60, 72, 62, 64), notes.map { it.pitch })
        assertEquals(1, moved)
        val (merged, _) = MidiImport.foldOntoTwoOctaves(
            listOf(SongNote(0, 480, 60), SongNote(0, 240, 84), SongNote(0, 100, 72), SongNote(480, 480, 62), SongNote(960, 480, 64)),
        )
        // C6 (84) folds onto C5 (72), where there is already a note: the longer one is kept.
        assertEquals(listOf(SongNote(0, 480, 60), SongNote(0, 240, 72), SongNote(480, 480, 62), SongNote(960, 480, 64)), merged)
        assertEquals(emptyList<SongNote>() to 0, MidiImport.foldOntoTwoOctaves(emptyList()))
    }

    // ---- accompaniment ----

    @Test
    fun `the reader keeps how hard each note was played`() {
        val file = MidiFileReader.read(resource("format0.mid"))!!
        assertEquals(listOf(80, 100, 100, 100, 100, 100), file.tracks.single().notes.map { it.velocity })
    }

    @Test
    fun `the other parts become the accompaniment, on their own channels and instruments`() {
        val file = MidiFileReader.read(resource("format1.mid"))!!
        val result = MidiImport.toSong(file, MidiImport.parts(file), 0, topNotesOnly = true, id = "i", title = "T")
        assertEquals(2, result.backingParts)
        val song = result.song
        // The player's part is unchanged by having an accompaniment.
        assertEquals(listOf(72, 74, 76, 79), song.notes.map { it.pitch })
        // Bass (channel 1) and drums (channel 9), at 480 ticks a beat and three quarters as loud (90 -> 68).
        assertEquals(
            listOf(BackingNote(0, 960, 36, 68, 1), BackingNote(960, 960, 43, 68, 1), BackingNote(1920, 960, 36, 68, 1)),
            song.backing.filter { it.channel == 1 },
        )
        assertEquals(listOf(0, 480, 960, 1440), song.backing.filter { it.channel == 9 }.map { it.startTick })
        assertEquals(mapOf(1 to 32), song.backingPrograms) // Acoustic Bass; drums need no instrument
        assertEquals(2880, song.endTick)
    }

    @Test
    fun `choosing the bass instead leaves the melody in the accompaniment`() {
        val file = MidiFileReader.read(resource("format1.mid"))!!
        val song = MidiImport.toSong(file, MidiImport.parts(file), 1, topNotesOnly = true, id = "i", title = "T").song
        assertEquals(listOf(36, 43, 36), song.notes.map { it.pitch })
        assertEquals(listOf(72, 74, 76, 79), song.backing.filter { it.channel == 0 }.map { it.pitch })
        assertEquals(mapOf(0 to 73), song.backingPrograms) // Flute
    }

    @Test
    fun `chord notes the player is spared are played by the accompaniment instead`() {
        val file = MidiFileReader.read(resource("format0.mid"))!!
        val parts = MidiImport.parts(file)
        val line = MidiImport.toSong(file, parts, 0, topNotesOnly = true, id = "i", title = "T").song
        assertEquals(listOf(67, 64, 67), line.notes.map { it.pitch })
        // The C and E under the first chord, on the part's own channel, plus the channel-2 bass note.
        assertEquals(listOf(60, 64), line.backing.filter { it.channel == 0 }.map { it.pitch })
        assertEquals(listOf(48), line.backing.filter { it.channel == 1 }.map { it.pitch })
        val chords = MidiImport.toSong(file, parts, 0, topNotesOnly = false, id = "i", title = "T").song
        assertEquals(emptyList<Int>(), chords.backing.filter { it.channel == 0 }.map { it.pitch })
    }

    @Test
    fun `an introduction before the player comes in is kept, and silence before any sound is dropped`() {
        val file = MidiFile(
            format = 1, ticksPerBeat = 480, bpm = 100.0,
            tracks = listOf(
                MidiTrack("Tune", listOf(MidiNote(2880, 480, 60, 0)), emptyMap()),
                MidiTrack("Pad", listOf(MidiNote(960, 1920, 48, 1, 100)), mapOf(1 to 88)),
            ),
        )
        val song = MidiImport.toSong(file, MidiImport.parts(file), 0, topNotesOnly = true, id = "i", title = "T").song
        assertEquals(listOf(BackingNote(0, 1920, 48, 75, 1)), song.backing) // starts the piece
        assertEquals(listOf(1920), song.notes.map { it.startTick })          // the player enters two bars in... after the pad
    }

    private fun played(song: Song, steps: List<Double>, also: (BackingPlayer, Int) -> Unit = { _, _ -> }): List<String> {
        val out = ArrayList<String>()
        val player = BackingPlayer(song) { status, d1, d2 -> out += "%02X %d %d".format(status, d1, d2) }
        player.start()
        steps.forEachIndexed { index, position ->
            player.moveTo(position)
            also(player, index)
        }
        return out
    }

    private val backedSong = Song(
        "b", "B", "", 100, notes = listOf(SongNote(0, 480, 60)),
        backing = listOf(
            BackingNote(0, 480, 48, 70, 1), BackingNote(480, 480, 48, 70, 1), // the same note twice, back to back
            BackingNote(0, 240, 36, 90, 9),
            BackingNote(960, 480, 55, 70, 1),
        ),
        backingPrograms = mapOf(1 to 32),
    )

    @Test
    fun `the accompaniment sets its instruments and then plays notes as the song passes them`() {
        assertEquals(
            listOf(
                "C1 32 0",                 // instrument for channel 2; drums get none
                "91 48 70", "99 36 90",    // position 0
                "89 36 0",                 // 240: the drum note ends
                "81 48 0", "91 48 70",     // 480: release first, then the repeat
            ),
            played(backedSong, listOf(-100.0, 0.0, 100.0, 240.0, 480.0)),
        )
    }

    @Test
    fun `while the song is held nothing more is sent, and releasing stops what is sounding`() {
        val out = played(backedSong, listOf(0.0, 0.0, 0.0)) { player, index -> if (index == 1) player.release() }
        assertEquals(listOf("C1 32 0", "91 48 70", "99 36 90", "81 48 0", "89 36 0"), out)
        // After a release, the notes' own endings do not send a second release.
        val later = played(backedSong, listOf(0.0, 300.0)) { player, index -> if (index == 0) player.release() }
        assertEquals(listOf("C1 32 0", "91 48 70", "99 36 90", "81 48 0", "89 36 0"), later)
    }

    @Test
    fun `a leap forward releases what was sounding and does not play the notes leapt over`() {
        val out = played(backedSong, listOf(0.0, 960.0)) // from the first beat straight to the third
        assertEquals(
            listOf("C1 32 0", "91 48 70", "99 36 90", "81 48 0", "89 36 0", "91 55 70"),
            out,
        )
    }

    @Test
    fun `a song with no accompaniment sends nothing`() {
        assertEquals(emptyList<String>(), played(Songs.byId("twinkle")!!, listOf(0.0, 480.0, 5000.0)))
    }

    // ---- keeping imported songs ----

    @Test
    fun `an imported song survives being written and read back`() {
        val file = MidiFileReader.read(resource("format0.mid"))!!
        val song = MidiImport.toSong(file, MidiImport.parts(file), 0, false, "import:My song", "My song").song
        assertEquals(song, SongCodec.decode(SongCodec.encode(song), "import:My song"))
        // And one with an accompaniment and instruments.
        val full = MidiFileReader.read(resource("format1.mid"))!!
        val backed = MidiImport.toSong(full, MidiImport.parts(full), 0, true, "import:B", "B").song
        assertTrue(backed.backing.isNotEmpty())
        assertEquals(backed, SongCodec.decode(SongCodec.encode(backed), "import:B"))
        assertTrue(SongCodec.encode(backed).lines().containsAll(listOf("p 1 32", "b 0 960 36 68 1")))
        assertNull(SongCodec.decode("lumisong 1\ntitle A\nn 0 480 60\nb 0 480 60 0 1\n", "x"))   // velocity 0
        assertNull(SongCodec.decode("lumisong 1\ntitle A\nn 0 480 60\nb 0 480 60 90 16\n", "x")) // no such channel
        assertNull(SongCodec.decode("lumisong 1\ntitle A\nn 0 480 60\np 1 200\n", "x"))          // no such instrument
        assertEquals(
            listOf("lumisong 1", "title My song", "credit Imported", "bpm 120", "n 0 480 60"),
            SongCodec.encode(song).lines().take(5),
        )
    }

    @Test
    fun `song files that are damaged or empty are refused`() {
        assertNull(SongCodec.decode("", "x"))
        assertNull(SongCodec.decode("lumisong 2\ntitle A\nn 0 480 60\n", "x"))
        assertNull(SongCodec.decode("lumisong 1\ntitle A\n", "x"))                  // no notes
        assertNull(SongCodec.decode("lumisong 1\nn 0 480 60\n", "x"))               // no title
        assertNull(SongCodec.decode("lumisong 1\ntitle A\nn 0 480 200\n", "x"))     // no such key
        assertNull(SongCodec.decode("lumisong 1\ntitle A\nn 0 0 60\n", "x"))        // no length
        assertNull(SongCodec.decode("lumisong 1\ntitle A\nbpm fast\nn 0 480 60\n", "x"))
        val ok = SongCodec.decode("lumisong 1\ntitle A B\nfuture thing\nbpm 999\nn 480 480 62\nn 0 480 60\n", "x")!!
        assertEquals("A B", ok.title)
        assertEquals(300, ok.bpm)
        assertEquals(listOf(0, 480), ok.notes.map { it.startTick }) // sorted
        assertFalse(ok.notes.isEmpty())
    }
}
