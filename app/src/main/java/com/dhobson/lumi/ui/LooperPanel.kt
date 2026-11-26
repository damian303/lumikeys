package com.dhobson.lumi.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dhobson.lumi.loop.LayerColors
import com.dhobson.lumi.loop.LoopMidi
import com.dhobson.lumi.loop.LoopProject
import com.dhobson.lumi.loop.LoopStore
import com.dhobson.lumi.loop.Looper
import com.dhobson.lumi.loop.NoteSpan
import com.dhobson.lumi.loop.Snap
import kotlin.math.roundToInt

private fun layerColor(index: Int): Color {
    check(LumiStyle.layerPalette.size == LayerColors.COUNT)
    return LumiStyle.layerPalette[Math.floorMod(index, LumiStyle.layerPalette.size)]
}

/** What a MIDI file is called when asking the system for somewhere to save one. */
private const val MIDI_MIME_TYPE = "audio/midi"

/** From this panel width up, a layer's Mute button sits in its row; below, it is with volume and delete. */
private val WIDE_PANEL: Dp = 700.dp

/** Height of a layer's row when it is not opened up. */
private val LAYER_ROW_HEIGHT: Dp = 54.dp

/** Space to the right of the timelines; the bar numbers use the same so they line up. */
private val TIMELINE_END_INSET: Dp = 10.dp

/**
 * Record, layer and play back a loop. One row of controls, then one slim row per
 * layer, laid out like tracks: the layer's name on the left and its notes across the
 * loop on the right. Tapping a layer's name opens its volume and delete.
 */
@Composable
fun LooperPanel(
    ui: Looper.Ui,
    looper: Looper,
    /** Called with the place the user picked to save the loop as a MIDI file. */
    onExportMidi: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Not read here: the timelines read it while drawing, so the moving playhead
    // repaints those strips without rebuilding anything.
    val progress = looper.progress.collectAsStateWithLifecycle()
    var showLoops by rememberSaveable { mutableStateOf(false) }
    var openLayer by rememberSaveable { mutableStateOf<Int?>(null) }
    // The system's "save file" screen: the user chooses where the MIDI file goes.
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIDI_MIME_TYPE)) { uri ->
        if (uri != null) onExportMidi(uri)
    }
    if (showLoops) {
        LoopsDialog(
            ui, looper,
            onExport = {
                looper.dismissNotice() // a "saved" from an earlier export must not look like this one's
                exporter.launch("${ui.name ?: LoopMidi.DEFAULT_TITLE}.mid")
            },
            onDismiss = { showLoops = false },
        )
    }

    BoxWithConstraints(modifier) {
        val wide = maxWidth >= WIDE_PANEL
        val labelWidth = if (wide) 290.dp else 124.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ControlBar(ui, looper, onShowLoops = { showLoops = true })
            ui.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            ui.info?.let {
                Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
            }
            if (ui.layers.isEmpty()) {
                EmptyLoopHint(Modifier.weight(1f).fillMaxWidth())
            } else {
                BarRuler(ui.bars, labelWidth)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(ui.layers, key = { it.id }) { layer ->
                        LayerRow(
                            layer = layer,
                            wide = wide,
                            labelWidth = labelWidth,
                            open = openLayer == layer.id,
                            onToggleOpen = { openLayer = if (openLayer == layer.id) null else layer.id },
                            lengthTicks = ui.lengthTicks,
                            beats = ui.bars * ui.beatsPerBar,
                            beatsPerBar = ui.beatsPerBar,
                            progress = { progress.value },
                            onMute = { looper.toggleMute(layer.id) },
                            onDelete = { looper.deleteLayer(layer.id) },
                            onVolume = { looper.setVolume(layer.id, it) },
                        )
                    }
                }
            }
        }
        CountInOverlay(looper, Modifier.align(Alignment.Center))
    }
}

/** Everything for running the loop, in one row where the width allows; it wraps on a narrow screen. */
@Composable
private fun ControlBar(ui: Looper.Ui, looper: Looper, onShowLoops: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = looper::toggleRecord,
            enabled = ui.ready,
            colors = if (ui.recording) {
                ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB3202C))
            } else {
                ButtonDefaults.buttonColors(containerColor = LumiStyle.record, contentColor = LumiStyle.onRecord)
            },
        ) {
            if (ui.recording) StopGlyph() else DotGlyph()
            Spacer(Modifier.width(8.dp))
            Text(if (ui.recording) "Finish layer" else "Record", maxLines = 1, softWrap = false)
        }

        FilledTonalButton(onClick = looper::togglePlay, enabled = ui.ready) {
            if (ui.running) StopGlyph() else PlayGlyph()
            Spacer(Modifier.width(8.dp))
            Text(if (ui.running) "Stop" else "Play", maxLines = 1, softWrap = false)
        }

        PositionDisplay(looper, Modifier.width(128.dp))

        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { looper.changeTempo(-5) },
                    enabled = !ui.recording && ui.tempoBpm > LoopProject.MIN_TEMPO,
                    modifier = Modifier.semantics { contentDescription = "Slower" },
                ) { Text("\u2212", style = MaterialTheme.typography.titleLarge) }
                Text("${ui.tempoBpm} bpm", style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
                TextButton(
                    onClick = { looper.changeTempo(5) },
                    enabled = !ui.recording && ui.tempoBpm < LoopProject.MAX_TEMPO,
                    modifier = Modifier.semantics { contentDescription = "Faster" },
                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
        }

        // The length is fixed once something is recorded: shortening would cut it off.
        MenuChip(
            label = barsLabel(ui.bars),
            description = "Loop length: ${barsLabel(ui.bars)}",
            highlighted = false,
            enabled = ui.layers.isEmpty(),
            choices = LoopProject.BAR_CHOICES,
            current = ui.bars,
            choiceLabel = ::barsLabel,
            choiceDescription = { "Loop length ${barsLabel(it)}" },
            onSelect = looper::setBars,
        )

        FilterChip(
            selected = ui.metronome,
            onClick = looper::toggleMetronome,
            label = { Text("Click", maxLines = 1, softWrap = false) },
        )

        // Snap tidies loose timing by pulling note starts onto a grid when the loop plays.
        MenuChip(
            label = "Snap: ${ui.snap.label}",
            description = "Snap to grid: ${ui.snap.label}",
            highlighted = ui.snap != Snap.OFF,
            enabled = true,
            choices = Snap.entries,
            current = ui.snap,
            choiceLabel = { it.label },
            choiceDescription = { "Snap to ${it.label}" },
            onSelect = looper::setSnap,
        )

        FilterChip(
            selected = false,
            onClick = onShowLoops,
            label = {
                Text(
                    ui.name?.let { "Loop: $it" } ?: "Loops\u2026",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
            },
            modifier = Modifier.semantics { contentDescription = "Save or open loops" },
        )

        TextButton(
            onClick = looper::undoLastLayer,
            enabled = ui.layers.isNotEmpty(),
            modifier = Modifier.semantics { contentDescription = "Undo last layer" },
        ) { Text("Undo layer", maxLines = 1, softWrap = false) }
    }
}

/**
 * Bar and beat. Collects the position itself so that only this small piece is redrawn
 * on each beat, not the buttons around it.
 */
@Composable
private fun PositionDisplay(looper: Looper, modifier: Modifier = Modifier) {
    val position by looper.position.collectAsStateWithLifecycle()
    Column(modifier) {
        val current = position
        Text(
            when {
                current == null -> "Stopped"
                current.countInBeats > 0 -> "Count-in: ${current.countInBeats}"
                else -> "Bar ${current.bar}  Beat ${current.beat}"
            },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BeatDots(current = if (current != null && current.countInBeats == 0) current.beat else 0)
    }
}

/** One dot per beat of the bar; the current beat is filled. Not the only cue: the text above says the same. */
@Composable
private fun BeatDots(current: Int, beatsPerBar: Int = 4) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) {
        for (beat in 1..beatsPerBar) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(
                        if (beat == current) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
            )
        }
    }
}

/** A large number over the panel during the count-in, so it is obvious when to start playing. */
@Composable
private fun CountInOverlay(looper: Looper, modifier: Modifier = Modifier) {
    val position by looper.position.collectAsStateWithLifecycle()
    val beatsLeft = position?.countInBeats ?: 0
    if (beatsLeft > 0) CountdownBadge(beatsLeft, modifier)
}

/** The big "3 / Get ready" shown while counting in, in the looper and in the learn mode. */
@Composable
internal fun CountdownBadge(number: Int, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(LumiStyle.accent)
            .semantics { contentDescription = "Count-in: $number" }
            .padding(horizontal = 44.dp, vertical = 14.dp),
    ) {
        Text("$number", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Get ready", style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

/** What to do, shown while the loop has no layers. */
@Composable
private fun EmptyLoopHint(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Make a loop", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tap the instrument name to choose a sound.\n" +
                    "Press Record, wait for the count-in, then play.\n" +
                    "What you play repeats. Press Record again to add another sound on top.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun barsLabel(bars: Int): String = if (bars == 1) "1 bar" else "$bars bars"

/** A chip that shows the current choice and opens a menu of the others when tapped. */
@Composable
internal fun <T> MenuChip(
    label: String,
    description: String,
    highlighted: Boolean,
    enabled: Boolean,
    choices: List<T>,
    current: T,
    choiceLabel: (T) -> String,
    choiceDescription: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = highlighted,
            enabled = enabled,
            onClick = { open = true },
            label = { Text(if (enabled) "$label \u25BE" else label, maxLines = 1, softWrap = false) },
            modifier = Modifier.semantics {
                contentDescription = if (enabled) "$description. Tap to change." else description
            },
        )
        // A long list (the songs) runs off the bottom of the screen with nothing to show
        // that it scrolls, so in that case it starts with a line saying so.
        val scroll = rememberScrollState()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, scrollState = scroll) {
            if (scroll.maxValue > 0) {
                Text(
                    "${choices.size} to choose from. Scroll down for more.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            for (choice in choices) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (choice == current) "${choiceLabel(choice)}  \u2713" else choiceLabel(choice),
                            fontWeight = if (choice == current) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(choice)
                    },
                    modifier = Modifier.semantics { contentDescription = choiceDescription(choice) },
                )
            }
        }
    }
}

/** Bar numbers above the layer timelines; each number sits at the start of its bar. */
@Composable
private fun BarRuler(bars: Int, labelWidth: Dp) {
    Row(Modifier.fillMaxWidth().padding(end = TIMELINE_END_INSET).semantics { contentDescription = "$bars bars" }) {
        Spacer(Modifier.width(labelWidth))
        for (bar in 1..bars) {
            Text(
                "$bar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 3.dp),
            )
        }
    }
}

/**
 * One layer as a track: a strip in the layer's colour, its name, and its notes across
 * the loop. Tapping the name opens volume and delete underneath.
 */
@Composable
private fun LayerRow(
    layer: Looper.LayerUi,
    wide: Boolean,
    labelWidth: Dp,
    open: Boolean,
    onToggleOpen: () -> Unit,
    lengthTicks: Int,
    beats: Int,
    beatsPerBar: Int,
    progress: () -> Float,
    onMute: () -> Unit,
    onDelete: () -> Unit,
    onVolume: (Int) -> Unit,
) {
    val count = if (layer.noteCount == 1) "1 note" else "${layer.noteCount} notes"
    val color = layerColor(layer.colorIndex)
    val quiet = layer.muted || layer.volume == 0
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (layer.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(Modifier.height(LAYER_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(labelWidth).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                onClickLabel = if (open) "Hide volume and delete" else "Show volume and delete",
                                onClick = onToggleOpen,
                            )
                            .semantics { contentDescription = "Layer ${layer.name}" },
                    ) {
                        Box(Modifier.width(6.dp).fillMaxHeight().background(color.copy(alpha = if (quiet) 0.4f else 1f)))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                layer.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when {
                                    layer.recording -> "Recording, $count"
                                    layer.muted -> "Muted, $count"
                                    else -> count
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (layer.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            if (open) "\u25B4" else "\u25BE",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                    if (wide) {
                        MuteChip(layer, onMute)
                        Spacer(Modifier.width(8.dp))
                    }
                }
                LayerTimeline(
                    notes = layer.notes,
                    lengthTicks = lengthTicks,
                    beats = beats,
                    beatsPerBar = beatsPerBar,
                    noteColor = color.copy(alpha = if (quiet) 0.35f else 1f),
                    progress = progress,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = 6.dp, bottom = 6.dp, end = TIMELINE_END_INSET)
                        .semantics { contentDescription = "Timeline: $count across $beats beats" },
                )
            }
            AnimatedVisibility(visible = open) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                ) {
                    if (!wide) MuteChip(layer, onMute)
                    Text("Volume", style = MaterialTheme.typography.bodyMedium)
                    VolumeSlider(
                        layer.name, layer.volume, onVolume,
                        if (wide) Modifier.width(320.dp) else Modifier.weight(1f),
                    )
                    Text("${layer.volume}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (wide) Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.semantics { contentDescription = "Delete layer ${layer.name}" },
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

@Composable
private fun MuteChip(layer: Looper.LayerUi, onMute: () -> Unit) {
    FilterChip(
        selected = layer.muted,
        onClick = onMute,
        label = { Text(if (layer.muted) "Muted" else "Mute", maxLines = 1, softWrap = false) },
        modifier = Modifier.semantics {
            contentDescription = if (layer.muted) "Unmute ${layer.name}" else "Mute ${layer.name}"
        },
    )
}

/** Layer loudness. Writes when the finger lifts, so dragging does not rebuild the loop on every step. */
@Composable
private fun VolumeSlider(layerName: String, volume: Int, onVolume: (Int) -> Unit, modifier: Modifier = Modifier) {
    var position by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
    Slider(
        value = position,
        onValueChange = { position = it },
        onValueChangeFinished = { onVolume(position.roundToInt()) },
        valueRange = 0f..100f,
        modifier = modifier.semantics { contentDescription = "Volume of $layerName" },
    )
}

/**
 * The layer's notes laid out across the loop: left to right is time, with a line on
 * every beat (stronger on each bar); higher notes sit higher. A vertical line shows
 * where playback is. Notes are drawn where they are heard, so with Snap on they
 * start on the grid lines.
 */
@Composable
private fun LayerTimeline(
    notes: List<NoteSpan>,
    lengthTicks: Int,
    beats: Int,
    beatsPerBar: Int,
    noteColor: Color,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.surfaceContainerLowest
    val gridColor = MaterialTheme.colorScheme.outline
    val playheadColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier) {
        val width = size.width
        val height = size.height
        drawRoundRect(background, cornerRadius = CornerRadius(8.dp.toPx()))
        if (lengthTicks <= 0 || beats <= 0) return@Canvas

        // Beat and bar lines.
        for (beat in 0..beats) {
            val x = width * beat / beats
            val isBar = beat % beatsPerBar == 0
            drawLine(
                color = gridColor.copy(alpha = if (isBar) 0.8f else 0.28f),
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = (if (isBar) 1.5f else 1f).dp.toPx(),
            )
        }

        // Notes: pitch decides the height within the strip.
        if (notes.isNotEmpty()) {
            val lowest = notes.minOf { it.note }
            val highest = notes.maxOf { it.note }
            val rows = highest - lowest + 1
            val inset = 4.dp.toPx()
            val noteHeight = ((height - 2 * inset) / rows).coerceIn(4.dp.toPx(), 10.dp.toPx())
            val travel = height - 2 * inset - noteHeight
            val minWidth = 5.dp.toPx()
            val corner = CornerRadius(noteHeight / 2)
            for (span in notes) {
                val y = if (rows == 1) (height - noteHeight) / 2 else inset + travel * (highest - span.note) / (rows - 1)
                val x = width * span.startTick / lengthTicks
                val w = (width * span.lengthTicks / lengthTicks).coerceAtLeast(minWidth)
                drawRoundRect(noteColor, topLeft = Offset(x, y), size = Size(minOf(w, width - x), noteHeight), cornerRadius = corner)
                if (x + w > width) { // held past the loop end: the rest shows at the start
                    drawRoundRect(noteColor, topLeft = Offset(0f, y), size = Size(x + w - width, noteHeight), cornerRadius = corner)
                }
            }
        }

        // Playhead. Reading progress here means only this drawing reruns as it moves.
        // A bright line with a soft glow, so it cannot be mistaken for a bar line.
        val at = progress()
        if (at >= 0f) {
            val x = (width * at).coerceIn(0f, width)
            drawLine(playheadColor.copy(alpha = 0.22f), Offset(x, 0f), Offset(x, height), strokeWidth = 8.dp.toPx())
            drawLine(playheadColor, Offset(x, 0f), Offset(x, height), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Save the current loop under a name, open or delete saved ones, export it, or start afresh. */
@Composable
private fun LoopsDialog(ui: Looper.Ui, looper: Looper, onExport: () -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(ui.name.orEmpty()) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmNew by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Loops") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "The loop you are working on is kept by itself and comes back when the app is opened. " +
                        "Save it under a name to keep it while you make another.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(LoopStore.MAX_NAME_LENGTH) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { looper.saveAs(name) },
                        enabled = name.isNotBlank() && ui.layers.isNotEmpty(),
                    ) { Text("Save", maxLines = 1, softWrap = false) }
                }
                Text(
                    when {
                        ui.layers.isEmpty() -> "There is nothing to save yet."
                        ui.name != null && ui.name == LoopStore.cleanName(name) ->
                            "Saved as \"${ui.name}\". Save again to keep later changes."
                        LoopStore.cleanName(name) in ui.savedLoops -> "Saving replaces the saved loop with this name."
                        ui.name != null -> "Saved as \"${ui.name}\"."
                        else -> "Not saved under a name yet."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ui.notice?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Text("Saved loops", style = MaterialTheme.typography.titleSmall)
                if (ui.savedLoops.isEmpty()) {
                    Text("None yet.", style = MaterialTheme.typography.bodyMedium)
                }
                for (saved in ui.savedLoops) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(saved, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (confirmDelete == saved) {
                            TextButton(
                                onClick = {
                                    confirmDelete = null
                                    looper.deleteSaved(saved)
                                },
                                modifier = Modifier.semantics { contentDescription = "Really delete $saved" },
                            ) { Text("Really delete?", color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = { confirmDelete = null }) { Text("Keep") }
                        } else {
                            TextButton(
                                onClick = {
                                    looper.open(saved)
                                    onDismiss()
                                },
                                modifier = Modifier.semantics { contentDescription = "Open $saved" },
                            ) { Text("Open") }
                            TextButton(
                                onClick = { confirmDelete = saved },
                                modifier = Modifier.semantics { contentDescription = "Delete $saved" },
                            ) { Text("Delete") }
                        }
                    }
                }

                Text("For other music apps", style = MaterialTheme.typography.titleSmall)
                Text(
                    "A MIDI file of the loop as it sounds now, played through once. Muted layers are left out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onExport, enabled = ui.canExport) {
                    Text("Export as a MIDI file\u2026", maxLines = 1, softWrap = false)
                }
                ui.info?.let {
                    Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
                }

                // Asks twice: this throws the current layers away (saved loops are not touched).
                OutlinedButton(
                    onClick = {
                        if (confirmNew) {
                            looper.newLoop()
                            onDismiss()
                        } else {
                            confirmNew = true
                        }
                    },
                    enabled = ui.layers.isNotEmpty(),
                ) {
                    Text(
                        if (confirmNew) "Really clear the current loop?" else "Start a new empty loop",
                        color = if (confirmNew) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
            }
        },
    )
}
