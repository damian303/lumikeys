package com.dhobson.lumi.loop

import android.util.Log
import com.dhobson.lumi.audio.NativeSynth
import com.dhobson.lumi.music.Instruments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the native loop sequencer from the [LoopProject] model: transport, recording
 * takes into layers, and edits. The timing itself all happens natively on the audio
 * clock; this class only decides what the loop contains.
 *
 * Main thread only. [scope] must dispatch on the main thread.
 */
class Looper(
    private val scope: CoroutineScope,
    /** The instrument currently selected for live playing; a new layer records with it. */
    private val currentInstrumentId: () -> Int,
    /** Reported audio output delay in ms, or negative if unknown. */
    private val outputLatencyMillis: () -> Int,
    /** Where loops are saved. Only touched on the IO dispatcher. */
    private val store: LoopStore,
) {
    data class LayerUi(
        val id: Int,
        val name: String,
        val noteCount: Int,
        val muted: Boolean,
        val recording: Boolean,
        /** The layer's notes as heard (snap applied), for its timeline. */
        val notes: List<NoteSpan>,
        /** Loudness, 0 to 100. */
        val volume: Int,
        val colorIndex: Int,
    )

    data class Ui(
        val ready: Boolean = false,
        val tempoBpm: Int = LoopProject.DEFAULT_TEMPO,
        val bars: Int = LoopProject.DEFAULT_BARS,
        val beatsPerBar: Int = 4,
        /** Loop length in the same units as [NoteSpan] positions. */
        val lengthTicks: Int = LoopProject().lengthTicks,
        val metronome: Boolean = true,
        val snap: Snap = Snap.BEAT,
        val running: Boolean = false,
        val recording: Boolean = false,
        val layers: List<LayerUi> = emptyList(),
        /** Name the loop was last saved or opened as, if any. */
        val name: String? = null,
        val savedLoops: List<String> = emptyList(),
        /** Whether there is anything in the loop that a MIDI file of it would contain. */
        val canExport: Boolean = false,
        /** A short explanation when something could not be done. */
        val notice: String? = null,
        /** A short confirmation that something was done. */
        val info: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    /**
     * Where the playhead is, or null when stopped. Kept apart from [ui] because it
     * changes on every beat, so only the position display is redrawn each beat rather
     * than the whole panel.
     */
    private val _position = MutableStateFlow<LoopPosition?>(null)
    val position: StateFlow<LoopPosition?> = _position

    /**
     * How far through the loop the playhead is, 0 until 1, or negative when stopped or
     * counting in. Updated about 25 times a second, so read it only while drawing.
     */
    private val _progress = MutableStateFlow(NO_PROGRESS)
    val progress: StateFlow<Float> = _progress

    private var project = LoopProject()
    private var sampleRate = 0
    private var metronome = true
    private var running = false
    private var nextLayerId = 1

    // The take being recorded, if any.
    private var recorder: TakeRecorder? = null
    private var recordingLayerId: Int? = null

    private var pollJob: Job? = null
    private val drainBuffer = LongArray(256)

    private var loopName: String? = null
    private var savedLoops: List<String> = emptyList()
    private var autosaveJob: Job? = null

    /**
     * Call once the audio engine is running; nothing works before this. [restored] is
     * the loop that was open when the app last ran, if there was one.
     */
    fun onEngineReady(engineSampleRate: Int, restored: LoopProject? = null, restoredName: String? = null) {
        if (engineSampleRate <= 0) return
        sampleRate = engineSampleRate
        if (restored != null && project.layers.isEmpty()) {
            project = restored
            loopName = restoredName
            nextLayerId = (restored.layers.maxOfOrNull { it.id } ?: 0) + 1
        }
        NativeSynth.loopSetMetronome(metronome)
        pushLoop()
        publish()
        refreshSavedLoops()
    }

    // ---- Transport -------------------------------------------------------------------------

    /** Starts a new layer with the current instrument, or finishes the one being recorded. */
    fun toggleRecord() {
        if (sampleRate <= 0) return
        if (recorder != null) {
            finishTake()
            publish()
            return
        }
        val instrumentId = currentInstrumentId()
        val channel = LoopChannels.allocate(project.layers, instrumentId)
        if (channel == null) {
            publish(notice = "All ${LoopChannels.melodic.size} instrument layers are in use. Delete one first.")
            return
        }
        val layer = Layer(
            id = nextLayerId++,
            instrumentId = instrumentId,
            channel = channel,
            colorIndex = LayerColors.allocate(project.layers),
        )
        project = project.withLayer(layer)
        recordingLayerId = layer.id
        recorder = TakeRecorder(
            lengthTicks = project.lengthTicks,
            lengthFrames = LoopTiming.lengthFrames(project, sampleRate),
            framesPerBeat = LoopTiming.framesPerBeat(sampleRate, project.tempoBpm),
            compensationFrames = (outputLatencyMillis().coerceAtLeast(0) * sampleRate / 1000),
        )
        pushLoop()
        // Anything captured in the instant after the previous take ended belongs to no take.
        while (NativeSynth.loopDrainRecorded(drainBuffer) == drainBuffer.size) {
            // discard
        }
        if (running) {
            NativeSynth.loopSetRecording(true)
        } else {
            // From a standstill: one bar of clicks, then the loop starts with recording on.
            NativeSynth.loopStart(project.beatsPerBar, true)
            running = true
        }
        startPolling()
        publish()
    }

    fun togglePlay() {
        if (sampleRate <= 0) return
        if (running) {
            stop()
        } else {
            pushLoop()
            NativeSynth.loopStart(0, false)
            running = true
            startPolling()
            publish()
        }
    }

    fun stop() {
        if (!running && recorder == null) return
        if (recorder != null) finishTake()
        NativeSynth.loopStop()
        running = false
        publish()
    }

    // ---- Edits -----------------------------------------------------------------------------

    fun changeTempo(delta: Int) {
        if (recorder != null) return // a take's timing maths assumes a fixed tempo
        val tempo = (project.tempoBpm + delta).coerceIn(LoopProject.MIN_TEMPO, LoopProject.MAX_TEMPO)
        if (tempo == project.tempoBpm) return
        project = project.copy(tempoBpm = tempo)
        pushLoop()
        projectChanged()
        publish()
    }

    /** The loop length can only change while it is empty; shortening would cut recordings. */
    fun setBars(bars: Int) {
        if (bars !in LoopProject.BAR_CHOICES || project.layers.isNotEmpty()) return
        project = project.copy(bars = bars)
        pushLoop()
        projectChanged()
        publish()
    }

    /**
     * Chooses the grid notes are pulled onto when the loop plays. Recordings keep their
     * real timing, so this can be changed or turned off at any time.
     */
    fun setSnap(snap: Snap) {
        if (snap == project.snap) return
        project = project.copy(snap = snap)
        Log.i(TAG, "snap ${snap.name}: " + project.layers.joinToString("; ") { layer ->
            "layer ${layer.id} note starts " + LoopQuantizer.apply(layer.events, snap.gridTicks, project.lengthTicks)
                .filter { it.status == 0x90 }.take(12).map { it.tick }
        })
        pushLoop()
        projectChanged()
        publish()
    }

    fun toggleMetronome() {
        metronome = !metronome
        NativeSynth.loopSetMetronome(metronome)
        publish()
    }

    fun toggleMute(layerId: Int) {
        val layer = project.layers.firstOrNull { it.id == layerId } ?: return
        project = project.updateLayer(layerId) { it.copy(muted = !it.muted) }
        if (!layer.muted) silenceChannel(layer.channel) // being muted now: stop what is sounding
        pushLoop()
        projectChanged()
        publish()
    }

    /** Sets a layer's loudness, 0 to 100. Takes effect from the next note the layer plays. */
    fun setVolume(layerId: Int, volume: Int) {
        val layer = project.layers.firstOrNull { it.id == layerId } ?: return
        val target = volume.coerceIn(0, LayerVolume.FULL)
        if (target == layer.volume) return
        project = project.updateLayer(layerId) { it.copy(volume = target) }
        if (target == 0) silenceChannel(layer.channel)
        pushLoop()
        projectChanged()
        publish()
    }

    fun deleteLayer(layerId: Int) {
        val layer = project.layers.firstOrNull { it.id == layerId } ?: return
        if (layerId == recordingLayerId) {
            NativeSynth.loopSetRecording(false)
            recorder = null
            recordingLayerId = null
        }
        project = project.withoutLayer(layerId)
        silenceChannel(layer.channel)
        pushLoop()
        projectChanged()
        publish()
    }

    /** Removes the most recently added layer. */
    fun undoLastLayer() {
        project.layers.lastOrNull()?.let { deleteLayer(it.id) }
    }

    fun dismissNotice() = publish()

    // ---- Saving ----------------------------------------------------------------------------

    /** Saves the loop under [rawName], replacing any saved loop of the same name. */
    fun saveAs(rawName: String) {
        val name = LoopStore.cleanName(rawName)
        if (name == null) {
            publish(notice = "Give the loop a name made of letters or numbers.")
            return
        }
        if (recorder != null) finishTake()
        val text = LoopCodec.encode(project)
        scope.launch {
            val saved = withContext(Dispatchers.IO) { store.save(name, text) }
            savedLoops = withContext(Dispatchers.IO) { store.list() }
            if (saved) {
                loopName = name
                projectChanged() // so the name is remembered with the current loop
            }
            Log.i(TAG, "save '$name': ${if (saved) "ok" else "FAILED"}, ${project.layers.size} layers")
            publish(notice = if (saved) null else "The loop could not be saved.")
        }
    }

    /** Replaces the current loop with a saved one. */
    fun open(name: String) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { store.read(name) }?.let(LoopCodec::decode)
            Log.i(TAG, "open '$name': ${loaded?.layers?.size ?: "FAILED"} layers")
            if (loaded == null) {
                publish(notice = "\"$name\" could not be opened.")
            } else {
                replaceProject(loaded, name)
            }
        }
    }

    fun deleteSaved(name: String) {
        scope.launch {
            withContext(Dispatchers.IO) { store.delete(name) }
            savedLoops = withContext(Dispatchers.IO) { store.list() }
            if (loopName == name) {
                loopName = null
                projectChanged()
            }
            publish()
        }
    }

    /**
     * The loop as a MIDI file for other music programs (see [LoopMidi] for what goes in
     * it), or null, with a notice, if nothing in the loop would be heard.
     */
    fun midiFile(): ByteArray? {
        if (recorder != null) finishTake()
        if (LoopMidi.audibleLayers(project).isEmpty()) {
            publish(notice = "There is nothing to put in a MIDI file: record a layer, or unmute one.")
            return null
        }
        return LoopMidi.encode(project, loopName)
    }

    /** Reports how writing the file from [midiFile] went. */
    fun onMidiFileWritten(written: Boolean) {
        Log.i(TAG, "MIDI export: ${if (written) "ok" else "FAILED"}, ${LoopMidi.audibleLayers(project).size} layers")
        if (written) publish(info = "Saved as a MIDI file.") else publish(notice = "The MIDI file could not be saved.")
    }

    /** Starts again with an empty loop, keeping tempo, length and snap. Saved loops are untouched. */
    fun newLoop() {
        replaceProject(project.copy(layers = emptyList()), name = null)
    }

    private fun refreshSavedLoops() {
        scope.launch {
            savedLoops = withContext(Dispatchers.IO) { store.list() }
            publish(notice = _ui.value.notice)
        }
    }

    private fun replaceProject(replacement: LoopProject, name: String?) {
        if (recorder != null) {
            NativeSynth.loopSetRecording(false)
            recorder = null
            recordingLayerId = null
        }
        if (running) {
            NativeSynth.loopStop()
            running = false
        }
        project.layers.forEach { silenceChannel(it.channel) }
        project = replacement
        nextLayerId = (replacement.layers.maxOfOrNull { it.id } ?: 0) + 1
        loopName = name
        pushLoop()
        projectChanged()
        publish()
    }

    /**
     * Keeps the current loop on disk so it is still there the next time the app starts.
     * Written shortly after the last change rather than on every one.
     */
    private fun projectChanged() {
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            delay(AUTOSAVE_DELAY_MS)
            val text = LoopCodec.encodeWithName(project, loopName)
            val written = withContext(Dispatchers.IO) { store.writeAutosave(text) }
            if (!written) Log.w(TAG, "could not autosave the loop")
        }
    }

    // ---- Internals -------------------------------------------------------------------------

    private fun finishTake() {
        val take = recorder ?: return
        val layerId = recordingLayerId
        NativeSynth.loopSetRecording(false)
        val captured = drainCaptured(take)
        val released = take.closeHeld(NativeSynth.loopState()[STATE_POSITION].coerceAtLeast(0))
        recorder = null
        recordingLayerId = null
        if (layerId != null) {
            project = project.updateLayer(layerId) { it.copy(events = it.events + captured + released) }
            val layer = project.layers.firstOrNull { it.id == layerId }
            Log.i(TAG, "take finished: layer $layerId, ${layer?.noteCount ?: 0} notes at ticks " +
                "${layer?.events?.filter { it.status == 0x90 }?.take(12)?.map { it.tick }}")
            if (layer != null && layer.noteCount == 0) {
                project = project.withoutLayer(layerId) // nothing was played
            }
        }
        pushLoop()
        projectChanged()
    }

    /** Pulls newly captured notes from the native side and converts them. */
    private fun drainCaptured(take: TakeRecorder): List<LoopEvent> {
        val events = ArrayList<LoopEvent>()
        while (true) {
            val count = NativeSynth.loopDrainRecorded(drainBuffer)
            for (i in 0 until count) {
                val frame = drainBuffer[i] ushr 32
                val message = (drainBuffer[i] and 0xFFFFFFFFL).toInt()
                take.onCaptured(frame, message)?.let { events += it }
            }
            if (count < drainBuffer.size) break
        }
        return events
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (running) {
                // Merge what has been played so far, so it is heard on the next pass.
                val take = recorder
                val layerId = recordingLayerId
                if (take != null && layerId != null) {
                    val captured = drainCaptured(take)
                    if (captured.isNotEmpty()) {
                        project = project.updateLayer(layerId) { it.copy(events = it.events + captured) }
                        pushLoop()
                        publish(notice = _ui.value.notice) // new notes to show
                    }
                }
                publishPosition()
                delay(POLL_INTERVAL_MS)
            }
            publishPosition()
        }
    }

    private fun pushLoop() {
        if (sampleRate <= 0) return
        val compiled = LoopCompiler.compile(project, sampleRate)
        NativeSynth.loopSet(
            compiled.frames, compiled.messages, compiled.lengthFrames, compiled.framesPerBeat, compiled.beatsPerBar,
        )
    }

    private fun silenceChannel(channel: Int) {
        NativeSynth.sendMidi(0xB0 or channel, CC_ALL_NOTES_OFF, 0)
    }

    /** The playhead only; cheap enough to call on every poll. */
    private fun publishPosition() {
        if (!running || sampleRate <= 0) {
            _position.value = null
            _progress.value = NO_PROGRESS
            return
        }
        val frame = NativeSynth.loopState()[STATE_POSITION]
        _position.value = LoopPosition.at(
            frame, LoopTiming.framesPerBeat(sampleRate, project.tempoBpm), project.beatsPerBar,
        )
        val lengthFrames = LoopTiming.lengthFrames(project, sampleRate)
        _progress.value = if (frame >= 0 && lengthFrames > 0) {
            (frame.toFloat() / lengthFrames).coerceIn(0f, 1f)
        } else {
            NO_PROGRESS
        }
    }

    /** Everything the panel shows. Call after any change to the project or transport. */
    private fun publish(notice: String? = null, info: String? = null) {
        publishPosition()
        _ui.value = Ui(
            ready = sampleRate > 0,
            tempoBpm = project.tempoBpm,
            bars = project.bars,
            beatsPerBar = project.beatsPerBar,
            lengthTicks = project.lengthTicks,
            metronome = metronome,
            snap = project.snap,
            running = running,
            recording = recorder != null,
            layers = project.layers.map {
                LayerUi(
                    id = it.id,
                    name = Instruments.byId(it.instrumentId)?.name ?: "Instrument",
                    noteCount = it.noteCount,
                    muted = it.muted,
                    recording = it.id == recordingLayerId,
                    notes = LoopNotes.spans(it.events, project.snap.gridTicks, project.lengthTicks),
                    volume = it.volume,
                    colorIndex = it.colorIndex,
                )
            },
            name = loopName,
            savedLoops = savedLoops,
            canExport = LoopMidi.audibleLayers(project).isNotEmpty(),
            notice = notice,
            info = info,
        )
        if (LOG_LOOP) Log.v(TAG, "loop: running=$running recording=${recorder != null} layers=" +
            project.layers.joinToString { "${it.id}:${it.noteCount}n ch${it.channel}${if (it.muted) " muted" else ""}" })
    }

    private companion object {
        const val TAG = "LumiLoop"
        const val LOG_LOOP = false
        const val POLL_INTERVAL_MS = 40L
        const val STATE_POSITION = 2
        const val NO_PROGRESS = -1f
        const val AUTOSAVE_DELAY_MS = 400L
        const val CC_ALL_NOTES_OFF = 123
    }
}
