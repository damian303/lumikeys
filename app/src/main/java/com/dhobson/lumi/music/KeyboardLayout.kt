package com.dhobson.lumi.music

import kotlin.math.abs

/** Geometry of the on-screen keyboard: two octaves starting on a C. Pure maths, no UI types. */
object KeyboardLayout {
    const val OCTAVES = 2
    const val WHITE_KEYS = 7 * OCTAVES
    const val KEY_COUNT = 12 * OCTAVES

    /** Black keys cover this fraction of the keyboard height, from the top. */
    const val BLACK_HEIGHT_FRACTION = 0.62f

    /** Black key width as a fraction of a white key's width. */
    const val BLACK_WIDTH_FRACTION = 0.6f

    private val whiteSemitones = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    /** Semitone of the black key to the right of each white key in an octave, or -1 if none. */
    private val blackAfterWhite = intArrayOf(1, 3, -1, 6, 8, 10, -1)

    fun whiteNote(baseNote: Int, whiteIndex: Int): Int =
        baseNote + 12 * (whiteIndex / 7) + whiteSemitones[whiteIndex % 7]

    /** The black key sitting on the boundary to the right of [whiteIndex], or null. */
    fun blackNoteAfter(baseNote: Int, whiteIndex: Int): Int? {
        val semitone = blackAfterWhite[whiteIndex % 7]
        return if (semitone < 0) null else baseNote + 12 * (whiteIndex / 7) + semitone
    }

    /** The note under a touch at ([x], [y]) on a keyboard of the given size. */
    fun noteAt(x: Float, y: Float, width: Float, height: Float, baseNote: Int): Int {
        val whiteWidth = width / WHITE_KEYS
        val clampedX = x.coerceIn(0f, width - 0.001f)
        if (y < height * BLACK_HEIGHT_FRACTION) {
            val boundary = (clampedX / whiteWidth + 0.5f).toInt() // nearest white-key boundary
            val leftWhite = boundary - 1
            val onBlack = abs(clampedX - boundary * whiteWidth) <= whiteWidth * BLACK_WIDTH_FRACTION / 2
            if (onBlack && leftWhite in 0 until WHITE_KEYS) {
                blackNoteAfter(baseNote, leftWhite)?.let { return it }
            }
        }
        val whiteIndex = (clampedX / whiteWidth).toInt().coerceIn(0, WHITE_KEYS - 1)
        return whiteNote(baseNote, whiteIndex)
    }

    /**
     * Where a key sits across the keyboard: its left edge and its width, both as
     * fractions of the keyboard's width. Null if [note] is not on the keyboard.
     * Used to line falling notes up with their keys.
     */
    fun keySpan(note: Int, baseNote: Int): Pair<Float, Float>? {
        val offset = note - baseNote
        if (offset !in 0 until KEY_COUNT) return null
        val octave = offset / 12
        val semitone = offset % 12
        val white = whiteSemitones.indexOf(semitone)
        if (white >= 0) {
            return (octave * 7 + white).toFloat() / WHITE_KEYS to 1f / WHITE_KEYS
        }
        // A black key is centred on the boundary to the right of the white key below it.
        val below = whiteSemitones.indexOf(semitone - 1)
        val width = BLACK_WIDTH_FRACTION / WHITE_KEYS
        return (octave * 7 + below + 1).toFloat() / WHITE_KEYS - width / 2 to width
    }

    /** Shifts [baseNote] by whole octaves, as little as possible, so that [note] is on the keyboard. */
    fun baseNoteContaining(baseNote: Int, note: Int): Int {
        var base = baseNote
        while (note < base && base >= 12) base -= 12
        while (note >= base + KEY_COUNT && base + 12 <= MAX_BASE_NOTE) base += 12
        return base
    }

    fun noteName(note: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        return names[note % 12] + (note / 12 - 1)
    }

    const val MIN_BASE_NOTE = 0
    /** The keyboard's highest octave setting (5) starts at C9; keys above G9 (127) are not MIDI notes. */
    const val MAX_BASE_NOTE = 108
}
