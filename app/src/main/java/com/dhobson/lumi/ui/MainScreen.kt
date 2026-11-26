package com.dhobson.lumi.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dhobson.lumi.EngineState
import com.dhobson.lumi.Session
import com.dhobson.lumi.learn.Learn
import com.dhobson.lumi.learn.PlayerRoster
import com.dhobson.lumi.midi.MidiConnection.Link
import com.dhobson.lumi.music.Instruments
import com.dhobson.lumi.music.KeyboardLayout

/** Reported output delay above which playing in time is not realistic. */
private const val SLOW_OUTPUT_MILLIS = 100

/** Space left and right of the on-screen keyboard; the falling notes use the same so they line up. */
private val KEYBOARD_INSET: Dp = 8.dp

/** Below this width (phones, or tablets at a large display size) the header wraps onto several lines. */
private val COMPACT_WIDTH: Dp = 600.dp

/** Height of the on-screen keyboard when it is folded down to a strip. */
private val KEY_STRIP_HEIGHT: Dp = 44.dp

/**
 * One header row, then the looper (Play) or the falling notes (Learn) using all the
 * room there is, then the on-screen keyboard. With a real keyboard connected the
 * on-screen one folds down to a strip that still shows which keys are held.
 */
@Composable
fun MainScreen(session: Session) {
    val engine by session.engineState.collectAsStateWithLifecycle()
    val connection by session.connection.collectAsStateWithLifecycle()
    val instrumentId by session.instrumentId.collectAsStateWithLifecycle()
    val favourites by session.favourites.collectAsStateWithLifecycle()
    val baseNote by session.baseNote.collectAsStateWithLifecycle()
    // State that changes many times a second (held keys, the status line, the loop
    // position) is deliberately NOT collected here: it is read in small composables of
    // its own, so a change redraws only that piece instead of the whole screen.
    val loop by session.looper.ui.collectAsStateWithLifecycle()
    val players by session.players.collectAsStateWithLifecycle()
    var showConnect by rememberSaveable { mutableStateOf(false) }
    var showPlayers by rememberSaveable { mutableStateOf(false) }
    var showInstruments by rememberSaveable { mutableStateOf(false) }
    var learnMode by rememberSaveable { mutableStateOf(false) }

    // Full-size keys when the screen is the only keyboard; a strip when a real one is
    // connected. The Keys button overrides that until the keyboard comes or goes.
    val keyboardConnected = connection is Link.Connected
    var keysChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(keyboardConnected) { keysChoice = null }
    val fullKeys = keysChoice ?: !keyboardConnected

    if (showConnect) {
        ConnectDialog(session, onDismiss = { showConnect = false })
    }
    if (showPlayers) {
        PlayersDialog(
            roster = players,
            onSelect = session::selectPlayer,
            onAdd = session::addPlayer,
            onRename = session::renamePlayer,
            onRemove = session::removePlayer,
            onDismiss = { showPlayers = false },
        )
    }
    if (showInstruments) {
        InstrumentDialog(
            selectedId = instrumentId,
            favourites = favourites,
            onSelect = {
                session.selectInstrument(it)
                showInstruments = false
            },
            onToggleFavourite = session::toggleFavourite,
            onDismiss = { showInstruments = false },
        )
    }

    Box(Modifier.fillMaxSize().background(LumiStyle.backdrop)) {
        Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val compact = maxWidth < COMPACT_WIDTH
                val keyboardHeight by animateDpAsState(
                    targetValue = if (fullKeys) (maxHeight * 0.28f).coerceIn(110.dp, 220.dp) else KEY_STRIP_HEIGHT,
                    label = "keyboard height",
                )

                Column(Modifier.fillMaxSize()) {
                    Header(
                        compact = compact,
                        instrumentName = Instruments.byId(instrumentId)?.name.orEmpty(),
                        onInstrumentClick = { showInstruments = true },
                        learnMode = learnMode,
                        onPlayMode = {
                            session.learn.stop() // clears the keyboard's lights
                            learnMode = false
                        },
                        onLearnMode = { learnMode = true },
                        players = players,
                        onPlayerClick = { showPlayers = true },
                        baseNote = baseNote,
                        onShiftOctave = session::shiftOctave,
                        fullKeys = fullKeys,
                        onToggleKeys = { keysChoice = !fullKeys },
                        connection = connection,
                        midiSupported = session.midiSupported,
                        onConnectClick = { showConnect = true },
                    )
                    EngineStatus(engine, session, onRetry = session::startEngine)
                    SlowOutputWarning(session)

                    if (learnMode) {
                        // The notes fall straight onto the keys, so nothing sits between them.
                        LearnPanel(
                            session.learn,
                            keyboardInset = KEYBOARD_INSET,
                            onPickFile = session::importMidi,
                            playerName = players.current?.name,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        LooperPanel(loop, session.looper, onExportMidi = session::exportLoopMidi, modifier = Modifier.weight(1f))
                    }
                    LiveKeyboard(
                        session = session,
                        baseNote = baseNote,
                        strip = !fullKeys,
                        modifier = Modifier.fillMaxWidth().height(keyboardHeight).padding(horizontal = KEYBOARD_INSET),
                    )
                }
            }
        }
    }
}

/** The touch keyboard, redrawn by itself whenever the set of held keys changes. */
@Composable
private fun LiveKeyboard(session: Session, baseNote: Int, strip: Boolean, modifier: Modifier = Modifier) {
    val pressed by session.pressedNotes.collectAsStateWithLifecycle()
    val learn by session.learn.ui.collectAsStateWithLifecycle()
    PianoKeyboard(
        baseNote = baseNote,
        pressed = pressed,
        onNoteOn = { session.noteOn(it) },
        onNoteOff = session::noteOff,
        modifier = modifier,
        targets = if (learn.phase == Learn.Phase.PLAYING) learn.targets else emptySet(),
        strip = strip,
    )
}

@Composable
private fun Header(
    compact: Boolean,
    instrumentName: String,
    onInstrumentClick: () -> Unit,
    learnMode: Boolean,
    onPlayMode: () -> Unit,
    onLearnMode: () -> Unit,
    players: PlayerRoster,
    onPlayerClick: () -> Unit,
    baseNote: Int,
    onShiftOctave: (Int) -> Unit,
    fullKeys: Boolean,
    onToggleKeys: () -> Unit,
    connection: Link,
    midiSupported: Boolean,
    onConnectClick: () -> Unit,
) {
    val left: @Composable () -> Unit = {
        InstrumentButton(instrumentName, onInstrumentClick)
        FilterChip(selected = !learnMode, onClick = onPlayMode, label = { Text("Play") })
        FilterChip(selected = learnMode, onClick = onLearnMode, label = { Text("Learn") })
        PlayerButton(players, onPlayerClick)
    }
    val right: @Composable () -> Unit = {
        OctaveControl(baseNote, onShiftOctave)
        FilterChip(
            selected = fullKeys,
            onClick = onToggleKeys,
            label = { Text("Keys", maxLines = 1, softWrap = false) },
            modifier = Modifier.semantics {
                contentDescription = if (fullKeys) "On-screen keys: full size. Tap to shrink." else "On-screen keys: small. Tap to enlarge."
            },
        )
        ConnectionButton(connection, midiSupported, onConnectClick)
    }
    if (compact) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            left()
            right()
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            left()
            Spacer(Modifier.weight(1f))
            right()
        }
    }
}

/** The current instrument; tapping it opens the list of instruments. */
@Composable
private fun InstrumentButton(name: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        modifier = Modifier.widthIn(max = 340.dp).semantics { contentDescription = "Instrument: $name. Tap to change." },
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(" \u25BE", style = MaterialTheme.typography.titleLarge)
    }
}

/** Octave down, the range of the keys, octave up. Moves the real keyboard too when one is connected. */
@Composable
private fun OctaveControl(baseNote: Int, onShift: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { onShift(-1) },
                enabled = baseNote > KeyboardLayout.MIN_BASE_NOTE,
                modifier = Modifier.semantics { contentDescription = "Octave down" },
            ) { Text("\u2212", style = MaterialTheme.typography.titleLarge) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Octave", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${KeyboardLayout.noteName(baseNote)} to ${KeyboardLayout.noteName(baseNote + KeyboardLayout.KEY_COUNT - 1)}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            TextButton(
                onClick = { onShift(1) },
                enabled = baseNote < KeyboardLayout.MAX_BASE_NOTE,
                modifier = Modifier.semantics { contentDescription = "Octave up" },
            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
    }
}

/** The connection status doubles as the way into the Connect dialog (which also holds the settings). */
@Composable
private fun ConnectionButton(connection: Link, midiSupported: Boolean, onClick: () -> Unit) {
    if (!midiSupported) {
        Text(
            "This device has no MIDI support",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    OutlinedButton(onClick = onClick) {
        // The light repeats what the words say; it is not the only cue.
        DotGlyph(
            Modifier.size(10.dp),
            color = when (connection) {
                Link.None -> MaterialTheme.colorScheme.outline
                is Link.Connecting -> LumiStyle.connecting
                is Link.Connected -> LumiStyle.connected
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when (connection) {
                Link.None -> "Connect keyboard"
                is Link.Connecting -> "Connecting"
                is Link.Connected -> "${connection.name} (${connection.transport.label})"
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Loading and failure of the sound engine; once it is running, the optional audio details line. */
@Composable
private fun EngineStatus(engine: EngineState, session: Session, onRetry: () -> Unit) {
    when (engine) {
        EngineState.Loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Loading sounds", style = MaterialTheme.typography.bodyMedium)
        }
        is EngineState.Failed -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                "Sound is unavailable: ${engine.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f, fill = false),
            )
            FilledTonalButton(onClick = onRetry) { Text("Retry", maxLines = 1, softWrap = false) }
        }
        EngineState.Ready -> DiagnosticsLine(session)
    }
}

/** Shown while the audio output is too slow to play in time (typically Bluetooth audio). */
@Composable
private fun SlowOutputWarning(session: Session) {
    val outputLatency by session.outputLatencyMillis.collectAsStateWithLifecycle()
    if (outputLatency >= SLOW_OUTPUT_MILLIS) {
        Text(
            "Slow sound output (about $outputLatency ms), probably Bluetooth audio. " +
                "Use the built-in speaker or wired headphones.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
        )
    }
}

/** The audio status line, shown only if switched on in the Connect dialog; refreshes twice a second on its own. */
@Composable
private fun DiagnosticsLine(session: Session) {
    val show by session.showAudioDetails.collectAsStateWithLifecycle()
    if (!show) return
    val diagnostics by session.diagnostics.collectAsStateWithLifecycle()
    Text(
        diagnostics,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
    )
}

/** The list of instruments, over the screen. Choosing one closes it. */
@Composable
private fun InstrumentDialog(
    selectedId: Int,
    favourites: Set<Int>,
    onSelect: (Int) -> Unit,
    onToggleFavourite: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.88f),
        ) {
            Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
                ) {
                    Text("Choose an instrument", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                InstrumentPicker(
                    selectedId = selectedId,
                    favourites = favourites,
                    onSelect = onSelect,
                    onToggleFavourite = onToggleFavourite,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
