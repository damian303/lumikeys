package com.dhobson.lumi.learn

import android.util.Log
import com.dhobson.lumi.util.TextFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lighting the keys of the real keyboard. Implemented by the session, which owns the connection. */
interface LearnLights {
    /** A song is starting: put the keyboard into a light mode in which lit keys stand out. */
    fun begin()

    /**
     * Light exactly these keys (MIDI note numbers) and no others. Keys that are already
     * lit are lit again, in case the keyboard cleared one when the player pressed it.
     */
    fun show(keys: Set<Int>)

    /** The song has ended or been stopped: clear the lights and put the keyboard back as it was. */
    fun end()
}

/**
 * Runs the learn mode: which song, how fast, where it has got to, and which keys
 * are wanted. The rules themselves are in [LearnEngine]. Main thread only.
 *
 * Time comes from the screen's frame clock ([onFrame]). That is good enough here
 * because in wait mode nothing else is playing that the song must stay in step with.
 */
class Learn(
    /** Must dispatch on the main thread. */
    private val scope: CoroutineScope,
    /** Where imported songs are kept. Only touched on the IO dispatcher. */
    private val store: TextFolder,
    /** Lowest key of the keyboard as it is set now. */
    private val baseNote: () -> Int,
    private val lights: LearnLights,
    /** Sends a short MIDI message to the synth's accompaniment channels. */
    private val backingOut: (status: Int, data1: Int, data2: Int) -> Unit,
    /** Called as a song starts, to stop anything else that is making sound. */
    private val beforeStart: () -> Unit,
    /** Reported audio output delay in ms, or negative if unknown. */
    private val outputLatencyMillis: () -> Int,
    /** Best score so far for a key (song and mode), or null. Whose scores these are is the caller's business. */
    private val loadBest: (String) -> Int?,
    private val saveBest: (String, Int) -> Unit,
) {
    enum class Phase { IDLE, PLAYING, FINISHED }

    enum class Mode(val label: String) {
        /** The song stops at each note until the right key is pressed. */
        WAIT("Wait for me"),

        /** The song keeps going; key presses are judged on their timing. */
        TIMED("In time"),
    }

    /** How a finished go at a song went. */
    data class Outcome(
        val mode: Mode,
        val tally: Tally,
        /** Best score before this go, at full speed in this mode; null if there was none. */
        val previousBest: Int?,
        /** True if this go set a new best. Only goes at full speed count. */
        val newBest: Boolean,
        val fullSpeed: Boolean,
    )

    /** A MIDI file that has been opened and is waiting for the user to confirm how to import it. */
    data class ImportPreview(
        /** Suggested name, from the file's name. */
        val suggestedTitle: String,
        val parts: List<PartInfo>,
        val selectedPart: Int,
        val topNotesOnly: Boolean,
        /** What importing with the current choices would produce, in words. */
        val summary: String,
    )

    data class PartInfo(val name: String, val noteCount: Int, val range: String, val isDrums: Boolean)

    data class Ui(
        val songs: List<Song> = Songs.all,
        val songId: String = Songs.all.first().id,
        val speedPercent: Int = 100,
        val mode: Mode = Mode.WAIT,
        val phase: Phase = Phase.IDLE,
        /** The song as placed on the keyboard, for drawing. Empty until a song is started. */
        val notes: List<SongNote> = emptyList(),
        /** Lowest key of the keyboard the notes were placed for. */
        val baseNote: Int = 60,
        /** Keys to press now. */
        val targets: Set<Int> = emptySet(),
        /** Start tick of the notes to press now, or null. */
        val targetTick: Int? = null,
        /** What has become of each of [notes], in the same order. */
        val marks: List<Mark> = emptyList(),
        val tally: Tally? = null,
        /** "In time" mode: how the last key press was judged. */
        val lastJudgement: Judgement? = null,
        /** Set when a song has been played to the end. */
        val outcome: Outcome? = null,
        /** Best score so far for the chosen song in the chosen mode, at full speed. */
        val best: Int? = null,
        /** The number to show before the song starts (3, 2, 1), or 0 once it is under way. */
        val countdown: Int = 0,
        /** Whether the app plays the song's other parts along, for songs that have any. */
        val backingOn: Boolean = true,
        /** Set while the import dialog should be showing. */
        val importPreview: ImportPreview? = null,
        val message: String? = null,
    ) {
        val song: Song get() = songs.firstOrNull { it.id == songId } ?: songs.first()
        val songIsImported: Boolean get() = songId.startsWith(IMPORT_PREFIX)
        val songHasBacking: Boolean get() = song.backing.isNotEmpty()
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private var backing: BackingPlayer? = null

    /** How long the song has been held waiting for the player, and whether the accompaniment has been hushed. */
    private var waitedNanos = 0L
    private var hushed = false

    private var imported: List<Song> = emptyList()
    private var pendingFile: MidiFile? = null
    private var pendingParts: List<MidiPart> = emptyList()

    init {
        _ui.value = _ui.value.copy(best = loadBest(bestKey(_ui.value.songId, _ui.value.mode)))
        // Songs imported earlier.
        scope.launch {
            imported = withContext(Dispatchers.IO) {
                store.list().mapNotNull { name -> store.read(name)?.let { SongCodec.decode(it, IMPORT_PREFIX + name) } }
            }
            _ui.value = _ui.value.copy(songs = Songs.all + imported)
        }
    }

    private fun songById(id: String): Song? = _ui.value.songs.firstOrNull { it.id == id }

    /** Song position in ticks. Changes every frame: read it only while drawing. */
    private val _position = MutableStateFlow(0f)
    val position: StateFlow<Float> = _position

    private var engine: SongRun? = null
    private var fitted: Song? = null
    private var countdown: Countdown? = null

    fun selectSong(id: String) {
        if (songById(id) == null || id == _ui.value.songId) return
        stop()
        _ui.value = _ui.value.copy(
            songId = id, phase = Phase.IDLE, notes = emptyList(), outcome = null,
            best = loadBest(bestKey(id, _ui.value.mode)), message = null,
        )
    }

    fun setMode(mode: Mode) {
        if (mode == _ui.value.mode) return
        stop()
        _ui.value = _ui.value.copy(mode = mode, outcome = null, best = loadBest(bestKey(_ui.value.songId, mode)))
    }

    private fun bestKey(songId: String, mode: Mode) = "$songId|${mode.name}"

    /**
     * Somebody else is playing now. Best scores belong to a player, so a song under
     * way is stopped (its score would go to the wrong one), a result still on show
     * is cleared, and the best shown becomes the new player's.
     */
    fun onPlayerChanged() {
        stop()
        _ui.value = _ui.value.copy(
            phase = Phase.IDLE, notes = emptyList(), outcome = null, message = null,
            best = loadBest(bestKey(_ui.value.songId, _ui.value.mode)),
        )
    }

    fun setSpeed(percent: Int) {
        if (percent !in SPEEDS) return
        _ui.value = _ui.value.copy(speedPercent = percent)
    }

    /** Starts the chosen song from the beginning. */
    fun start() {
        stopQuietly()
        val song = songById(_ui.value.songId) ?: return
        val base = baseNote()
        val placed = SongFit.toWindow(song, base)
        if (placed == null) {
            _ui.value = _ui.value.copy(phase = Phase.IDLE, notes = emptyList(), message = "This song does not fit on the keyboard.")
            return
        }
        beforeStart()
        fitted = placed
        backing = if (_ui.value.backingOn && placed.backing.isNotEmpty()) {
            BackingPlayer(placed, backingOut).also { it.start() }
        } else {
            null
        }
        engine = when (_ui.value.mode) {
            Mode.WAIT -> LearnEngine(placed)
            Mode.TIMED -> {
                val ticksPerSecond = ticksPerSecond(placed)
                // Playing along to the accompaniment, the player is following sound that
                // left the app a little earlier. With no accompaniment they go by the screen.
                val delayMillis = if (backing != null) outputLatencyMillis().coerceIn(0, MAX_COMPENSATION_MS) else 0
                TimedEngine(placed, ticksPerSecond, compensationTicks = delayMillis / 1000.0 * ticksPerSecond)
            }
        }
        countdown = Countdown(COUNTDOWN_SECONDS)
        waitedNanos = 0
        hushed = false
        Log.i(TAG, "start '${song.id}' ${_ui.value.mode} at base $base, ${placed.notes.size} notes, speed ${_ui.value.speedPercent}%")
        lights.begin()
        publish(Phase.PLAYING, base)
        lights.show(engine?.targets.orEmpty().toSet())
    }

    // ---- Importing MIDI files ---------------------------------------------------------------

    /**
     * A file was picked. [file] is null if it could not be read as MIDI. Opens the
     * import dialog with a guess at which part is the tune.
     */
    fun offerImport(fileName: String, file: MidiFile?) {
        val parts = file?.let(MidiImport::parts).orEmpty()
        val guess = MidiImport.likelyMelody(parts)
        if (file == null || guess == null) {
            Log.i(TAG, "import '$fileName': " + if (file == null) "not readable as MIDI" else "no notes")
            _ui.value = _ui.value.copy(
                importPreview = null,
                message = if (file == null) "\"$fileName\" is not a MIDI file this app can read." else "\"$fileName\" has no notes in it.",
            )
            return
        }
        stop()
        pendingFile = file
        pendingParts = parts
        Log.i(TAG, "import '$fileName': ${parts.size} parts, ${file.bpm} bpm, guess part $guess '${parts[guess].name}'")
        val title = TextFolder.cleanName(withoutExtensions(fileName)) ?: "Imported song"
        _ui.value = _ui.value.copy(importPreview = preview(title, guess, topNotesOnly = true), message = null)
    }

    fun setImportPart(index: Int) {
        val current = _ui.value.importPreview ?: return
        if (index !in pendingParts.indices) return
        _ui.value = _ui.value.copy(importPreview = preview(current.suggestedTitle, index, current.topNotesOnly))
    }

    fun setImportTopNotesOnly(topNotesOnly: Boolean) {
        val current = _ui.value.importPreview ?: return
        _ui.value = _ui.value.copy(importPreview = preview(current.suggestedTitle, current.selectedPart, topNotesOnly))
    }

    fun cancelImport() {
        pendingFile = null
        pendingParts = emptyList()
        _ui.value = _ui.value.copy(importPreview = null)
    }

    /** Adds the song as currently previewed to the list, under [rawTitle], and selects it. */
    fun confirmImport(rawTitle: String) {
        val current = _ui.value.importPreview ?: return
        val file = pendingFile ?: return
        val name = TextFolder.cleanName(rawTitle) ?: current.suggestedTitle
        val id = IMPORT_PREFIX + name
        val song = MidiImport.toSong(file, pendingParts, current.selectedPart, current.topNotesOnly, id, name).song
        val text = SongCodec.encode(song)
        cancelImport()
        scope.launch {
            val saved = withContext(Dispatchers.IO) { store.save(name, text) }
            Log.i(TAG, "imported '$name': ${song.notes.size} notes, saved=$saved")
            imported = (imported.filterNot { it.id == id } + song).sortedBy { it.title.lowercase() }
            _ui.value = _ui.value.copy(
                songs = Songs.all + imported,
                songId = id,
                phase = Phase.IDLE,
                notes = emptyList(),
                outcome = null,
                best = loadBest(bestKey(id, _ui.value.mode)),
                message = if (saved) null else "The song was added but could not be saved; it will be gone when the app closes.",
            )
        }
    }

    /** Removes an imported song. Built-in songs cannot be removed. */
    fun deleteImported(id: String) {
        if (!id.startsWith(IMPORT_PREFIX) || imported.none { it.id == id }) return
        stop()
        imported = imported.filterNot { it.id == id }
        val songs = Songs.all + imported
        val selected = if (_ui.value.songId == id) songs.first().id else _ui.value.songId
        _ui.value = _ui.value.copy(
            songs = songs,
            songId = selected,
            phase = Phase.IDLE,
            notes = emptyList(),
            outcome = null,
            best = loadBest(bestKey(selected, _ui.value.mode)),
        )
        scope.launch { withContext(Dispatchers.IO) { store.delete(id.removePrefix(IMPORT_PREFIX)) } }
    }

    private fun preview(title: String, partIndex: Int, topNotesOnly: Boolean): ImportPreview {
        val file = checkNotNull(pendingFile)
        val result = MidiImport.toSong(file, pendingParts, partIndex, topNotesOnly, "preview", title)
        val seconds = result.song.endTick.toDouble() / Song.PPQ / result.song.bpm * 60
        val summary = buildString {
            append("${result.song.notes.size} notes to play, about ${formatDuration(seconds)} at ${result.song.bpm} beats a minute.")
            if (result.leftOut > 0) append(" ${result.leftOut} lower notes of chords are played for you.")
            if (result.backingParts > 0) {
                append(if (result.backingParts == 1) " The other part plays along." else " The other ${result.backingParts} parts play along.")
            }
            if (result.movedByOctave > 0) {
                append(" ${result.movedByOctave} moved by an octave to fit the keyboard's two octaves, so parts of the tune may jump.")
            }
        }
        return ImportPreview(
            suggestedTitle = title,
            parts = pendingParts.map {
                PartInfo(it.name, it.noteCount, "${noteName(it.lowest)} to ${noteName(it.highest)}", it.isDrums)
            },
            selectedPart = partIndex,
            topNotesOnly = topNotesOnly,
            summary = summary,
        )
    }

    /** "Song.mid.rtx" (as one tablet's file picker names MIDI files) gives "Song". */
    private fun withoutExtensions(fileName: String): String {
        var name = fileName.trim()
        while (true) {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension !in FILE_EXTENSIONS) return name
            name = name.substringBeforeLast('.')
        }
    }

    private fun formatDuration(seconds: Double): String {
        val whole = Math.round(seconds).toInt()
        return when {
            whole == 1 -> "1 second"
            whole < 60 -> "$whole seconds"
            else -> "${whole / 60} min ${whole % 60} s"
        }
    }

    private fun noteName(pitch: Int): String =
        arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[pitch % 12] + (pitch / 12 - 1)

    // ---- Playing -----------------------------------------------------------------------------

    /** Stops the song where it is. */
    fun stop() {
        if (engine == null) return
        stopQuietly()
        _ui.value = _ui.value.copy(phase = Phase.IDLE, targets = emptySet(), countdown = 0, message = null)
    }

    private fun stopQuietly() {
        if (engine != null) lights.end()
        backing?.release()
        backing = null
        engine = null
        countdown = null
    }

    /** Turns the accompaniment on or off. Takes effect the next time a song is started. */
    fun setBacking(on: Boolean) {
        _ui.value = _ui.value.copy(backingOn = on)
    }

    /**
     * The keyboard's range changed (octave buttons): the notes on screen no longer
     * line up with the keys, so the song is stopped rather than left wrong.
     */
    fun onBaseNoteChanged() {
        if (engine == null) return
        stopQuietly()
        _ui.value = _ui.value.copy(
            phase = Phase.IDLE, targets = emptySet(), notes = emptyList(), countdown = 0,
            message = "The keyboard's octave changed. Press Start to begin again.",
        )
    }

    /** Call once per screen frame while a song is playing, with the time since the last frame. */
    fun onFrame(elapsedNanos: Long) {
        val current = engine ?: return
        val song = fitted ?: return
        // A long gap (the app was paused) must not make the song leap.
        var nanos = elapsedNanos.coerceIn(0, MAX_FRAME_NANOS)
        countdown?.let { counting ->
            nanos = counting.advance(nanos)
            if (!counting.running) countdown = null
            val shown = counting.secondsLeft
            if (shown != _ui.value.countdown) _ui.value = _ui.value.copy(countdown = shown)
            if (counting.running) return // the song has not started yet
        }
        val changed = current.advance(nanos / 1e9 * ticksPerSecond(song))
        _position.value = current.position.toFloat()
        if (changed) { // "in time" mode: a note went by unplayed
            lights.show(current.targets.toSet())
            publish(Phase.PLAYING, _ui.value.baseNote)
        }
        backing?.let { accompaniment ->
            accompaniment.moveTo(current.position)
            // Held at a note: after a moment, let go of what is sounding so it does not drone.
            if (current.waiting) {
                waitedNanos += nanos
                if (!hushed && waitedNanos > HUSH_AFTER_NANOS) {
                    accompaniment.release()
                    hushed = true
                }
            } else {
                waitedNanos = 0
                hushed = false
            }
        }
        if (current.finished) finish(current)
    }

    /** A key went down, on the real keyboard or on screen. */
    fun onKeyDown(pitch: Int) {
        val current = engine ?: return
        if (countdown != null) return // before the song starts keys only make sound
        val wanted = current.keyDown(pitch)
        if (wanted) {
            lights.show(current.targets.toSet())
            backing?.moveTo(current.position) // the song may have jumped forward to this note
        }
        publish(Phase.PLAYING, _ui.value.baseNote)
    }

    /**
     * A key came up. If it is one the song wants next (the same note twice in a row),
     * its light is sent again: it is not known whether the keyboard keeps a key lit
     * after the player has pressed and released it.
     */
    fun onKeyUp(pitch: Int) {
        val current = engine ?: return
        if (pitch in current.targets) lights.show(current.targets.toSet())
    }

    private fun ticksPerSecond(song: Song): Double = song.bpm * _ui.value.speedPercent / 100.0 / 60.0 * Song.PPQ

    private fun finish(current: SongRun) {
        val ui = _ui.value
        val tally = current.tally()
        val key = bestKey(ui.songId, ui.mode)
        val previous = loadBest(key)
        val fullSpeed = ui.speedPercent == 100
        val newBest = fullSpeed && tally.scorePercent > (previous ?: -1)
        if (newBest) saveBest(key, tally.scorePercent)
        Log.i(TAG, "finished ${ui.mode}: score ${tally.scorePercent}% ${tally.stars} stars, $tally, best before $previous")
        lights.end()
        backing?.release()
        backing = null
        engine = null
        _ui.value = ui.copy(
            phase = Phase.FINISHED,
            countdown = 0,
            targets = emptySet(),
            targetTick = null,
            marks = current.marks(),
            tally = tally,
            outcome = Outcome(ui.mode, tally, previous, newBest, fullSpeed),
            best = if (newBest) tally.scorePercent else previous,
        )
    }

    private fun publish(phase: Phase, base: Int) {
        val current = engine ?: return
        _position.value = current.position.toFloat()
        _ui.value = _ui.value.copy(
            phase = phase,
            notes = fitted?.notes.orEmpty(),
            baseNote = base,
            targets = current.targets.toSet(),
            targetTick = current.targetTick,
            marks = current.marks(),
            tally = current.tally(),
            lastJudgement = (current as? TimedEngine)?.lastJudgement,
            outcome = null,
            countdown = countdown?.secondsLeft ?: 0,
            message = null,
        )
    }

    companion object {
        private const val TAG = "LumiLearn"
        private const val MAX_FRAME_NANOS = 100_000_000L
        private const val COUNTDOWN_SECONDS = 3
        private const val HUSH_AFTER_NANOS = 400_000_000L
        private const val MAX_COMPENSATION_MS = 150
        private val FILE_EXTENSIONS = setOf("mid", "midi", "kar", "smf", "rtx")
        val SPEEDS = listOf(50, 75, 100)

        /** Ids of imported songs start with this; the rest is the name they are saved under. */
        const val IMPORT_PREFIX = "import:"
    }
}
