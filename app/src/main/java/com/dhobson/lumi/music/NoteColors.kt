package com.dhobson.lumi.music

/**
 * A colour for each of the twelve note names, the same in every octave, so the
 * screen can match the keyboard's rainbow lighting.
 *
 * These are an approximation. On the real keyboard C was seen as red, D orange,
 * E yellow, and F sharp and G blue; the rest are filled in around the colour wheel
 * and have not been compared with the keyboard key by key.
 */
object NoteColors {
    private val argb = intArrayOf(
        0xFFE53935.toInt(), // C   red
        0xFFF4511E.toInt(), // C#  red-orange
        0xFFFB8C00.toInt(), // D   orange
        0xFFFFB300.toInt(), // D#  amber
        0xFFFDD835.toInt(), // E   yellow
        0xFF43A047.toInt(), // F   green
        0xFF00ACC1.toInt(), // F#  cyan
        0xFF1E88E5.toInt(), // G   blue
        0xFF3949AB.toInt(), // G#  indigo
        0xFF8E24AA.toInt(), // A   purple
        0xFFD81B60.toInt(), // A#  magenta
        0xFFF06292.toInt(), // B   pink
    )

    /** Opaque ARGB colour for a MIDI note number. */
    fun argb(note: Int): Int = argb[Math.floorMod(note, 12)]
}
