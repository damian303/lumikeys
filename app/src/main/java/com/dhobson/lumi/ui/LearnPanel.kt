package com.dhobson.lumi.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dhobson.lumi.learn.Judgement
import com.dhobson.lumi.learn.Learn
import com.dhobson.lumi.learn.Mark
import com.dhobson.lumi.learn.Song
import com.dhobson.lumi.music.KeyboardLayout
import com.dhobson.lumi.music.NoteColors
import com.dhobson.lumi.util.TextFolder

/**
 * The learn mode: notes fall towards the on-screen keyboard directly below, each in
 * the lane above its key, and the song waits at each note for the right key.
 * [keyboardInset] is the keyboard's horizontal padding, so lanes and keys line up.
 */
@Composable
fun LearnPanel(
    learn: Learn,
    keyboardInset: Dp,
    /** Called with a file the user picked to import. */
    onPickFile: (Uri) -> Unit,
    /** Name of the player whose best scores are shown, or null when it is nobody in particular. */
    playerName: String?,
    modifier: Modifier = Modifier,
) {
    val ui by learn.ui.collectAsStateWithLifecycle()
    // Any file type is offered: tablets often do not recognise .mid files as MIDI.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPickFile(uri)
    }
    ui.importPreview?.let { ImportDialog(it, learn) }
    var confirmRemove by remember(ui.songId) { mutableStateOf(false) }
    // Read only while drawing, so the falling notes repaint without rebuilding the panel.
    val position = learn.position.collectAsStateWithLifecycle()

    if (ui.phase == Learn.Phase.PLAYING) {
        // The song's clock: one step per screen frame.
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (last != 0L) learn.onFrame(now - last)
                    last = now
                }
            }
        }
    }

    val song = ui.song
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            MenuChip(
                label = song.title,
                description = "Song: ${song.title}",
                highlighted = true,
                enabled = true,
                choices = ui.songs,
                current = song,
                choiceLabel = { it.title },
                choiceDescription = { "Song ${it.title}" },
                onSelect = { learn.selectSong(it.id) },
            )
            MenuChip(
                label = ui.mode.label,
                description = "Mode: ${ui.mode.label}",
                highlighted = false,
                enabled = ui.phase != Learn.Phase.PLAYING,
                choices = Learn.Mode.entries,
                current = ui.mode,
                choiceLabel = { it.label },
                choiceDescription = { "Mode ${it.label}" },
                onSelect = learn::setMode,
            )
            MenuChip(
                label = "Speed ${ui.speedPercent}%",
                description = "Speed: ${ui.speedPercent} percent",
                highlighted = false,
                enabled = true,
                choices = Learn.SPEEDS,
                current = ui.speedPercent,
                choiceLabel = { "$it%" },
                choiceDescription = { "Speed $it percent" },
                onSelect = learn::setSpeed,
            )
            if (ui.songHasBacking) {
                FilterChip(
                    selected = ui.backingOn,
                    onClick = { learn.setBacking(!ui.backingOn) },
                    enabled = ui.phase != Learn.Phase.PLAYING,
                    label = { Text("Backing", maxLines = 1, softWrap = false) },
                    modifier = Modifier.semantics {
                        contentDescription = if (ui.backingOn) "Backing on: the other parts play along" else "Backing off"
                    },
                )
            }
            Button(onClick = learn::start) {
                Text(if (ui.phase == Learn.Phase.IDLE) "Start" else "Start again", maxLines = 1, softWrap = false)
            }
            if (ui.phase == Learn.Phase.PLAYING) {
                OutlinedButton(onClick = learn::stop) { Text("Stop", maxLines = 1, softWrap = false) }
            }
            val status = statusText(ui, playerName)
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.titleSmall)
            if (ui.phase == Learn.Phase.IDLE && song.credit.isNotBlank()) {
                // Labelled, smaller and dimmer: a composer's name straight after or before
                // "Holly's best" read as if it were part of the player's name.
                Text(
                    "Tune: ${song.credit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.phase != Learn.Phase.PLAYING) {
                FilterChip(
                    selected = false,
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    label = { Text("Import MIDI file\u2026", maxLines = 1, softWrap = false) },
                )
                if (ui.songIsImported) {
                    // Asks twice: the song is deleted from the tablet.
                    TextButton(
                        onClick = {
                            if (confirmRemove) learn.deleteImported(ui.songId) else confirmRemove = true
                        },
                    ) {
                        Text(
                            if (confirmRemove) "Really remove \"${song.title}\"?" else "Remove this song",
                            color = if (confirmRemove) MaterialTheme.colorScheme.error else Color.Unspecified,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
        ui.message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = keyboardInset)) {
            FallingNotes(ui, position = { position.value }, modifier = Modifier.fillMaxSize())
            if (ui.phase == Learn.Phase.IDLE) {
                Text(
                    "Press Start. Notes fall towards the keys: press each key as its note lands. " +
                        "The song waits for you, and the keyboard lights the key to press.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            if (ui.countdown > 0) {
                CountdownBadge(ui.countdown, Modifier.align(Alignment.Center))
            }
            ui.outcome?.let { ResultCard(it, playerName, Modifier.align(Alignment.Center)) }
        }
    }
}

/** Choose how to bring a MIDI file in: its name, which part to play, and whether to simplify chords. */
@Composable
private fun ImportDialog(preview: Learn.ImportPreview, learn: Learn) {
    var title by rememberSaveable(preview.suggestedTitle) { mutableStateOf(preview.suggestedTitle) }
    AlertDialog(
        onDismissRequest = learn::cancelImport,
        confirmButton = { Button(onClick = { learn.confirmImport(title) }) { Text("Add song") } },
        dismissButton = { TextButton(onClick = learn::cancelImport) { Text("Cancel") } },
        title = { Text("Import song") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(TextFolder.MAX_NAME_LENGTH) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Which part to play", style = MaterialTheme.typography.titleSmall)
                preview.parts.forEachIndexed { index, part ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = index == preview.selectedPart,
                                role = Role.RadioButton,
                                onClick = { learn.setImportPart(index) },
                            )
                            .semantics { contentDescription = "Part ${index + 1}: ${part.name}" },
                    ) {
                        RadioButton(selected = index == preview.selectedPart, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(part.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${part.noteCount} notes, ${part.range}" + if (part.isDrums) " (drums: not a tune)" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = preview.topNotesOnly,
                            role = Role.Checkbox,
                            onValueChange = learn::setImportTopNotesOnly,
                        )
                        .semantics { contentDescription = "Top notes only" },
                ) {
                    Checkbox(checked = preview.topNotesOnly, onCheckedChange = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text("Top notes only", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Plays chords as a single line, one key at a time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(preview.summary, style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}

/** What to say beside the Start button; empty when there is nothing to say. */
private fun statusText(ui: Learn.Ui, playerName: String?): String = when (ui.phase) {
    Learn.Phase.IDLE -> ui.best?.let { if (playerName != null) "$playerName's best: $it%" else "Best: $it%" }.orEmpty()
    Learn.Phase.FINISHED -> "Finished!"
    Learn.Phase.PLAYING -> {
        val tally = ui.tally
        when {
            ui.countdown > 0 || tally == null -> "Get ready"
            ui.mode == Learn.Mode.WAIT -> {
                val slips = when (tally.wrong) {
                    0 -> ""
                    1 -> "   1 wrong key"
                    else -> "   ${tally.wrong} wrong keys"
                }
                "Note ${minOf(tally.done + 1, tally.total)} of ${tally.total}$slips"
            }
            else -> {
                val last = when (ui.lastJudgement) {
                    Judgement.PERFECT -> "Perfect!   "
                    Judgement.GOOD -> "Good   "
                    Judgement.WRONG -> "Wrong key   "
                    null -> ""
                }
                "$last${tally.perfect + tally.good} hit, ${tally.missed} missed"
            }
        }
    }
}

/** Stars, the score, and how it breaks down, over the falling-notes area when a song ends. */
@Composable
private fun ResultCard(outcome: Learn.Outcome, playerName: String?, modifier: Modifier = Modifier) {
    val tally = outcome.tally
    val detail = when (outcome.mode) {
        Learn.Mode.WAIT -> "${tally.perfect} of ${tally.total} right first time" +
            if (tally.wrong > 0) ", ${plural(tally.wrong, "wrong key")}" else ""
        Learn.Mode.TIMED -> listOfNotNull(
            "${tally.perfect} perfect",
            "${tally.good} good",
            "${tally.missed} missed",
            if (tally.wrong > 0) plural(tally.wrong, "wrong key") else null,
        ).joinToString(", ")
    }
    val best = when {
        !outcome.fullSpeed -> "Play at full speed to set a best score."
        outcome.newBest && outcome.previousBest != null -> "New best! (It was ${outcome.previousBest}%.)"
        outcome.newBest -> "Your first score for this song."
        else -> "Your best is ${outcome.previousBest}%."
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(2.dp, LumiStyle.accent),
        modifier = modifier.padding(16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
        ) {
            // Whose score this is, so it is plain it went to the right player.
            playerName?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary) }
            Text(
                "\u2605".repeat(tally.stars) + "\u2606".repeat(3 - tally.stars),
                style = MaterialTheme.typography.displayMedium,
                color = LumiStyle.gold,
                modifier = Modifier.semantics { contentDescription = "${tally.stars} of 3 stars" },
            )
            Text("Score ${tally.scorePercent}%", style = MaterialTheme.typography.headlineSmall)
            Text(detail, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Text(best, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/** How much of the song is visible above the keys: four beats. */
private const val LOOKAHEAD_TICKS = 4 * Song.PPQ

/**
 * Time runs downwards: a note's lower edge reaches the bottom of this view, which sits
 * on the keys, at the moment it is to be played, and its height is how long to hold it.
 * Each note is in the lane above its key and in that key's colour. Notes already
 * played are faded, missed ones are left as empty outlines, and the notes to press
 * now have a heavy outline.
 */
@Composable
private fun FallingNotes(ui: Learn.Ui, position: () -> Float, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surfaceContainerLowest
    val landing = MaterialTheme.colorScheme.surfaceContainerHigh
    val lineColor = MaterialTheme.colorScheme.outline
    val outline = MaterialTheme.colorScheme.onSurface
    val description = if (ui.phase == Learn.Phase.PLAYING) {
        "Falling notes. Press now: " + ui.targets.sorted().joinToString { KeyboardLayout.noteName(it) }
    } else {
        "Falling notes"
    }

    Canvas(modifier.clipToBounds().semantics { contentDescription = description }) {
        val width = size.width
        val height = size.height
        // Darker at the top, lifting towards the line the notes land on.
        drawRect(Brush.verticalGradient(listOf(background, landing)))
        if (ui.notes.isEmpty()) return@Canvas

        val now = position()
        fun yOf(tick: Float): Float = height - (tick - now) / LOOKAHEAD_TICKS * height

        // Faint lanes: one line at each white-key boundary, so a note can be traced down to its key.
        for (boundary in 1 until KeyboardLayout.WHITE_KEYS) {
            val x = width * boundary / KeyboardLayout.WHITE_KEYS
            drawLine(lineColor.copy(alpha = 0.18f), Offset(x, 0f), Offset(x, height), strokeWidth = 1.dp.toPx())
        }
        // A line on every beat, moving down with the notes.
        val firstBeat = Math.floorDiv(now.toInt(), Song.PPQ)
        for (beat in firstBeat..firstBeat + LOOKAHEAD_TICKS / Song.PPQ + 1) {
            val y = yOf((beat * Song.PPQ).toFloat())
            if (y in 0f..height) {
                drawLine(lineColor.copy(alpha = 0.35f), Offset(0f, y), Offset(width, y), strokeWidth = 1.dp.toPx())
            }
        }

        val corner = CornerRadius(5.dp.toPx())
        val gap = 2.dp.toPx()
        val outlineStroke = Stroke(width = 3.dp.toPx())
        val missedStroke = Stroke(width = 1.5.dp.toPx())
        ui.notes.forEachIndexed { index, note ->
            val bottom = yOf(note.startTick.toFloat())
            val top = yOf(note.endTick.toFloat()) + gap // a sliver of space between repeated notes
            if (bottom < 0f || top > height) return@forEachIndexed
            val (left, keyWidth) = KeyboardLayout.keySpan(note.pitch, ui.baseNote) ?: return@forEachIndexed
            val mark = ui.marks.getOrElse(index) { Mark.PENDING }
            val topLeft = Offset(left * width + gap, top)
            val noteSize = Size(keyWidth * width - 2 * gap, (bottom - top).coerceAtLeast(4.dp.toPx()))
            when (mark) {
                Mark.MISSED -> drawRoundRect(outline.copy(alpha = 0.45f), topLeft, noteSize, corner, style = missedStroke)
                Mark.HIT -> drawRoundRect(Color(NoteColors.argb(note.pitch)).copy(alpha = 0.25f), topLeft, noteSize, corner)
                Mark.PENDING -> {
                    drawRoundRect(Color(NoteColors.argb(note.pitch)), topLeft, noteSize, corner)
                    if (note.startTick == ui.targetTick) {
                        drawRoundRect(outline, topLeft, noteSize, corner, style = outlineStroke)
                    }
                }
            }
        }

        // The line the notes land on.
        drawLine(outline, Offset(0f, height), Offset(width, height), strokeWidth = 4.dp.toPx())
    }
}
