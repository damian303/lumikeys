package com.dhobson.lumi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.dhobson.lumi.music.KeyboardLayout
import com.dhobson.lumi.music.NoteColors

/**
 * Two-octave touch keyboard. Supports several fingers at once and sliding
 * between keys. [pressed] also shows notes held on the hardware keyboard.
 *
 * With [strip] it is drawn for a very short height: used when a real keyboard is
 * connected and this one is mostly there to show which keys are held.
 */
@Composable
fun PianoKeyboard(
    baseNote: Int,
    pressed: Set<Int>,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Keys the learn mode wants pressed next; each gets a marker. */
    targets: Set<Int> = emptySet(),
    strip: Boolean = false,
) {
    val currentNoteOn by rememberUpdatedState(onNoteOn)
    val currentNoteOff by rememberUpdatedState(onNoteOff)
    Canvas(
        modifier = modifier.pointerInput(baseNote) {
            val held = mutableMapOf<PointerId, Int>()
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            val previous = held[change.id]
                            if (change.pressed) {
                                val note = KeyboardLayout.noteAt(
                                    change.position.x,
                                    change.position.y,
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                    baseNote,
                                )
                                if (note != previous) {
                                    if (previous != null) currentNoteOff(previous)
                                    currentNoteOn(note)
                                    held[change.id] = note
                                }
                            } else if (previous != null) {
                                currentNoteOff(previous)
                                held.remove(change.id)
                            }
                            change.consume()
                        }
                    }
                }
            } finally {
                // The octave changed (or the keyboard left the screen) with keys still held.
                held.values.forEach { currentNoteOff(it) }
            }
        },
    ) {
        val whiteWidth = size.width / KeyboardLayout.WHITE_KEYS
        val gap = 3f
        val corner = CornerRadius(6f, 6f)

        // Dark bed so the gaps between white keys show on a light theme.
        drawRect(Color(0xFF1C1B1F))

        // Each key carries its note colour (the same colours as the keyboard's rainbow
        // lighting) as a band at its lower end; a held key is filled with it and outlined,
        // so "held" does not depend on telling colours apart.
        val pressedOutline = Stroke(width = 3.dp.toPx())
        val outlineColor = Color(0xFF1C1B1F)

        for (index in 0 until KeyboardLayout.WHITE_KEYS) {
            val note = KeyboardLayout.whiteNote(baseNote, index)
            val noteColor = Color(NoteColors.argb(note))
            val left = index * whiteWidth + gap / 2
            val keyWidth = whiteWidth - gap
            val held = note in pressed
            drawRoundRect(
                color = if (held) noteColor else Color.White,
                topLeft = Offset(left, 0f),
                size = Size(keyWidth, size.height),
                cornerRadius = corner,
            )
            if (held) {
                drawRoundRect(outlineColor, Offset(left, 0f), Size(keyWidth, size.height), corner, style = pressedOutline)
            } else {
                val band = size.height * (if (strip) STRIP_BAND_FRACTION else WHITE_BAND_FRACTION)
                drawRoundRect(noteColor, Offset(left, size.height - band), Size(keyWidth, band), corner)
            }
            if (note in targets && !held) {
                // "Press this one": a dark-ringed dot in the note's colour, a shape as well as a colour.
                val centre = Offset(left + keyWidth / 2, size.height * 0.74f)
                val radius = minOf(keyWidth * 0.3f, size.height * 0.2f)
                drawCircle(outlineColor, radius, centre)
                drawCircle(noteColor, radius * 0.62f, centre)
            }
        }

        val blackWidth = whiteWidth * KeyboardLayout.BLACK_WIDTH_FRACTION
        val blackHeight = size.height * KeyboardLayout.BLACK_HEIGHT_FRACTION
        for (index in 0 until KeyboardLayout.WHITE_KEYS) {
            val note = KeyboardLayout.blackNoteAfter(baseNote, index) ?: continue
            val noteColor = Color(NoteColors.argb(note))
            val left = (index + 1) * whiteWidth - blackWidth / 2
            val held = note in pressed
            drawRoundRect(
                color = if (held) noteColor else outlineColor,
                topLeft = Offset(left, 0f),
                size = Size(blackWidth, blackHeight),
                cornerRadius = corner,
            )
            if (held) {
                drawRoundRect(outlineColor, Offset(left, 0f), Size(blackWidth, blackHeight), corner, style = pressedOutline)
            } else {
                val band = blackHeight * (if (strip) STRIP_BAND_FRACTION else BLACK_BAND_FRACTION)
                val inset = 2.dp.toPx()
                drawRoundRect(
                    noteColor,
                    Offset(left + inset, blackHeight - band - inset),
                    Size(blackWidth - 2 * inset, band),
                    corner,
                )
            }
            if (note in targets && !held) {
                val centre = Offset(left + blackWidth / 2, blackHeight * 0.5f)
                val radius = minOf(blackWidth * 0.36f, blackHeight * 0.32f)
                drawCircle(Color.White, radius, centre)
                drawCircle(noteColor, radius * 0.62f, centre)
            }
        }
    }
}

/** Height of the note-colour band on a white key, as a fraction of the key. */
private const val WHITE_BAND_FRACTION = 0.14f

/** The same for both kinds of key when the keyboard is a short strip, so the colours still show. */
private const val STRIP_BAND_FRACTION = 0.42f

/** Height of the note-colour band on a black key, as a fraction of the key. */
private const val BLACK_BAND_FRACTION = 0.2f
