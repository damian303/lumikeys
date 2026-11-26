package com.dhobson.lumi.learn

/**
 * Songs for the learn mode, and the pure logic for reading and fitting them. No
 * Android types; all unit-tested.
 */

/** One note of the part the player plays. Positions are in ticks, [Song.PPQ] per beat. */
data class SongNote(val startTick: Int, val lengthTicks: Int, val pitch: Int) {
    val endTick: Int get() = startTick + lengthTicks
}

/**
 * A note of the accompaniment, which the app plays itself. [channel] is 0 to 15 within
 * the accompaniment's own bank of channels; 9 is drums, as in General MIDI.
 */
data class BackingNote(val startTick: Int, val lengthTicks: Int, val pitch: Int, val velocity: Int, val channel: Int)

data class Song(
    val id: String,
    val title: String,
    /** Who wrote it, or "Traditional". */
    val credit: String,
    val bpm: Int,
    /** The part the player plays. Sorted by start. */
    val notes: List<SongNote>,
    /** The other parts, played by the app alongside. On the same timeline as [notes]. */
    val backing: List<BackingNote> = emptyList(),
    /** General MIDI instrument for each accompaniment channel. */
    val backingPrograms: Map<Int, Int> = emptyMap(),
) {
    /** Where the song ends, accompaniment included. */
    val endTick: Int
        get() = maxOf(
            notes.maxOfOrNull { it.endTick } ?: 0,
            backing.maxOfOrNull { it.startTick + it.lengthTicks } ?: 0,
        )
    val lowest: Int get() = notes.minOf { it.pitch }
    val highest: Int get() = notes.maxOf { it.pitch }

    companion object {
        /** Ticks per beat. */
        const val PPQ = 480
    }
}

/**
 * A compact way to write a melody: notes separated by spaces, each `NAME:BEATS`.
 *
 *     C4:1 D4:1 E4:2 -:1 F#4:0.5 Bb3:1.5
 *
 * NAME is a letter A to G, an optional # or b, and an octave (C4 is middle C, MIDI 60);
 * `-` is a rest. BEATS may be a decimal. `|` may be used between bars and is ignored.
 */
object SongText {
    private val semitones = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    /** The notes, or null if any part of [text] is not understood. */
    fun parse(text: String): List<SongNote>? {
        val notes = ArrayList<SongNote>()
        var tick = 0
        for (word in text.split(Regex("\\s+"))) {
            if (word.isEmpty() || word == "|") continue
            val parts = word.split(':')
            if (parts.size != 2) return null
            val beats = parts[1].toDoubleOrNull() ?: return null
            val length = Math.round(beats * Song.PPQ).toInt()
            if (length <= 0) return null
            if (parts[0] != "-") {
                val pitch = pitchOf(parts[0]) ?: return null
                notes += SongNote(tick, length, pitch)
            }
            tick += length
        }
        return notes
    }

    /** MIDI note number for a name such as C4, F#3 or Bb5, or null. */
    fun pitchOf(name: String): Int? {
        if (name.length < 2) return null
        val base = semitones[name[0].uppercaseChar()] ?: return null
        val accidental = when (name[1]) {
            '#' -> 1
            'b' -> -1
            else -> 0
        }
        val octave = name.substring(if (accidental == 0) 1 else 2).toIntOrNull() ?: return null
        val pitch = 12 * (octave + 1) + base + accidental
        return pitch.takeIf { it in 0..127 }
    }
}

object SongFit {
    /** Number of keys on the keyboard. */
    const val KEYS = 24

    /**
     * [song] placed on a keyboard whose lowest key is [baseNote]: left at its written
     * pitch if every note is already on the keyboard, otherwise moved by as few whole
     * octaves as it takes. Null if the song spans more keys than the keyboard has, or
     * cannot be placed by octaves alone.
     */
    fun toWindow(song: Song, baseNote: Int): Song? {
        if (song.notes.isEmpty()) return song
        val top = baseNote + KEYS - 1
        val shift = when {
            song.lowest < baseNote -> 12 * ((baseNote - song.lowest + 11) / 12)  // up
            song.highest > top -> -12 * ((song.highest - top + 11) / 12)         // down
            else -> 0
        }
        if (song.lowest + shift < baseNote || song.highest + shift > top) return null
        if (shift == 0) return song
        return song.copy(notes = song.notes.map { it.copy(pitch = it.pitch + shift) })
    }
}
