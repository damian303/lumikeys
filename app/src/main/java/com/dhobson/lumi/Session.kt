package com.dhobson.lumi

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.io.File
import com.dhobson.lumi.audio.NativeSynth
import com.dhobson.lumi.audio.SoundFontInstaller
import com.dhobson.lumi.learn.Learn
import com.dhobson.lumi.learn.LearnLights
import com.dhobson.lumi.learn.MidiFileReader
import com.dhobson.lumi.learn.PlayerCodec
import com.dhobson.lumi.learn.PlayerRoster
import com.dhobson.lumi.loop.LoopCodec
import com.dhobson.lumi.loop.LoopStore
import com.dhobson.lumi.loop.Looper
import com.dhobson.lumi.midi.BluetoothMidiScanner
import com.dhobson.lumi.midi.BluetoothPermissions
import com.dhobson.lumi.midi.LumiProtocol
import com.dhobson.lumi.midi.LumiProtocol.DeviceMessage
import com.dhobson.lumi.midi.MidiConnection
import com.dhobson.lumi.midi.MidiConnection.Link
import com.dhobson.lumi.music.Instruments
import com.dhobson.lumi.music.KeyboardLayout
import com.dhobson.lumi.music.VelocityCurve
import com.dhobson.lumi.util.TextFolder
import kotlin.math.log10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface EngineState {
    data object Loading : EngineState
    data object Ready : EngineState
    data class Failed(val reason: String) : EngineState
}

/** What the app knows about the connected keyboard's own settings, read over SysEx. */
data class KeyboardInfo(
    val status: Status = Status.NOT_CONNECTED,
    /** Address for settings messages, learned from the keyboard's topology reply. */
    val deviceIndex: Int? = null,
    val firmware: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    /** Settings read so far, by config item id (see [LumiProtocol]). */
    val config: Map<Int, Setting> = emptyMap(),
) {
    enum class Status { NOT_CONNECTED, READING, READY, NO_ANSWER }

    data class Setting(val value: Int, val min: Int, val max: Int)

    fun setting(item: Int): Setting? = config[item]
}

/** What the Connect dialog needs to know about Bluetooth. */
data class BluetoothUi(
    /** The device has Bluetooth LE and the platform MIDI service. */
    val available: Boolean,
    val permissionGranted: Boolean = false,
    /** The user refused the permission request during this session. */
    val permissionDenied: Boolean = false,
    val enabled: Boolean = false,
    val scanning: Boolean = false,
    val devices: List<BluetoothMidiScanner.Found> = emptyList(),
    val scanError: String? = null,
    /** Keyboard that reconnects by itself whenever it is seen, if any. */
    val rememberedName: String? = null,
)

/**
 * Everything that must exist exactly once per app process: the audio engine, the
 * link to the keyboard, the Bluetooth scanner and the state the screen shows.
 *
 * This is deliberately not a ViewModel. The system can briefly have two instances
 * of the activity (for example when it restarts the task after an update), and a
 * per-screen owner would let the old instance shut down the native engine that the
 * new one is using. Owned by [LumiApp]; lives until the process dies.
 */
class Session(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = application.getSharedPreferences("lumi", Context.MODE_PRIVATE)

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Loading)
    val engineState: StateFlow<EngineState> = _engineState

    private val _instrumentId = MutableStateFlow(prefs.getInt(KEY_INSTRUMENT, 0))
    val instrumentId: StateFlow<Int> = _instrumentId

    private val _favourites = MutableStateFlow(
        prefs.getStringSet(KEY_FAVOURITES, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet(),
    )
    val favourites: StateFlow<Set<Int>> = _favourites

    private val _baseNote = MutableStateFlow(prefs.getInt(KEY_BASE_NOTE, DEFAULT_BASE_NOTE))
    val baseNote: StateFlow<Int> = _baseNote

    /** Notes currently held, from the hardware keyboard or the screen. */
    private val _pressedNotes = MutableStateFlow<Set<Int>>(emptySet())
    val pressedNotes: StateFlow<Set<Int>> = _pressedNotes

    /** How hard the hardware keys must be pressed. Read on the MIDI thread via its value. */
    private val _velocityCurve = MutableStateFlow(VelocityCurve.fromName(prefs.getString(KEY_VELOCITY_CURVE, null)))
    val velocityCurve: StateFlow<VelocityCurve> = _velocityCurve

    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics

    /** Output delay Android reports for the current audio route, in ms; -1 if unknown. */
    private val _outputLatencyMillis = MutableStateFlow(-1)
    val outputLatencyMillis: StateFlow<Int> = _outputLatencyMillis

    private val midi = MidiConnection(application, ::onHardwareMidi, ::onHardwareSysEx)
    val connection: StateFlow<Link> = midi.state
    val connectionError: StateFlow<String?> = midi.error
    val midiSupported: Boolean = midi.isSupported

    private val scanner = BluetoothMidiScanner(application)
    private val _bluetooth = MutableStateFlow(
        BluetoothUi(
            available = scanner.isAvailable && midi.isSupported,
            rememberedName = prefs.getString(KEY_BT_NAME, null),
        ),
    )
    val bluetooth: StateFlow<BluetoothUi> = _bluetooth

    /** The loop sequencer: records what is played into layers that repeat. */
    private val loopStore = LoopStore(File(application.filesDir, "loops"))
    val looper = Looper(
        scope,
        currentInstrumentId = { _instrumentId.value },
        outputLatencyMillis = { _outputLatencyMillis.value },
        store = loopStore,
    )

    /** Whether the technical audio status line is shown under the instrument name. */
    private val _showAudioDetails = MutableStateFlow(prefs.getBoolean(KEY_SHOW_AUDIO_DETAILS, false))
    val showAudioDetails: StateFlow<Boolean> = _showAudioDetails

    /**
     * Developer mode, off unless switched on in the Connect dialog: logs every key press
     * and keyboard message to logcat, and (in debuggable builds) accepts raw MIDI for the
     * keyboard from a computer over adb. Off for everyday use: the adb hook can be
     * reached by any app on the device.
     */
    private val _developerMode = MutableStateFlow(prefs.getBoolean(KEY_DEVELOPER_MODE, false))
    val developerMode: StateFlow<Boolean> = _developerMode

    /** Mirrors [developerMode] for the MIDI thread. */
    @Volatile
    private var logKeys = _developerMode.value

    private var debugReceiver: BroadcastReceiver? = null

    fun setDeveloperMode(on: Boolean) {
        _developerMode.value = on
        logKeys = on
        prefs.edit { putBoolean(KEY_DEVELOPER_MODE, on) }
        if (on) registerDebugBridge() else unregisterDebugBridge()
    }

    fun setShowAudioDetails(show: Boolean) {
        _showAudioDetails.value = show
        prefs.edit { putBoolean(KEY_SHOW_AUDIO_DETAILS, show) }
    }

    private val _keyboard = MutableStateFlow(KeyboardInfo())
    val keyboard: StateFlow<KeyboardInfo> = _keyboard

    /** Repeats the topology request until the keyboard answers. Main thread only. */
    private var handshake: Job? = null

    /** True once a silent Bluetooth link has been dropped to reconnect; cleared when a keyboard answers. */
    private var reconnectedAfterSilence = false

    // ---- Learn mode: lighting the real keyboard's keys. Main thread only. ----

    /** Keys this app has lit on the keyboard and not yet cleared. */
    private var litKeys: Set<Int> = emptySet()

    /** The keyboard's light mode before a song switched it, to put back afterwards. */
    private var modeBeforeLearn: Int? = null

    /** Best learn-mode scores, by player, song and mode (see [PlayerRoster.scoreKey]). */
    private val scores = application.getSharedPreferences("lumi_scores", Context.MODE_PRIVATE)

    /** Who is playing: each player has their own best scores. Main thread only. */
    private val _players = MutableStateFlow(PlayerCodec.decode(prefs.getString(KEY_PLAYERS, null)))
    val players: StateFlow<PlayerRoster> = _players

    private val learnLights = object : LearnLights {
        override fun begin() {
            val info = _keyboard.value
            val current = info.setting(LumiProtocol.ITEM_MODE)?.value ?: return
            val stage = (0..3).firstOrNull {
                info.setting(LumiProtocol.colourSchemeItem(it))?.value == LumiProtocol.COLOUR_SCHEME_STAGE
            } ?: return // no mode slot holds the Stage scheme: leave the keyboard as it is
            if (stage != current) {
                modeBeforeLearn = current
                setKeyboardSetting(LumiProtocol.ITEM_MODE, stage)
            }
        }

        override fun show(keys: Set<Int>) {
            // A plain note-on lights a key and a note-off clears it (tested on the keyboard).
            // The connection spaces these out, since the keyboard drops messages sent together.
            // Wanted keys are sent every time, not only new ones: see LearnLights.show.
            for (key in litKeys - keys) midi.send(byteArrayOf(0x80.toByte(), key.toByte(), 0))
            for (key in keys) midi.send(byteArrayOf(0x90.toByte(), key.toByte(), LIGHT_VELOCITY))
            if (logKeys) Log.d(TAG, "keyboard lights: $keys (sent=${midi.canSend})")
            litKeys = keys
        }

        override fun end() {
            show(emptySet())
            modeBeforeLearn?.let { setKeyboardSetting(LumiProtocol.ITEM_MODE, it) }
            modeBeforeLearn = null
        }
    }

    /** The learn mode: falling notes, wait for the right key. */
    val learn = Learn(
        scope = scope,
        store = TextFolder(File(application.filesDir, "songs"), ".lumisong"),
        baseNote = { _baseNote.value },
        lights = learnLights,
        backingOut = { status, data1, data2 -> NativeSynth.sendMidiToBank(NativeSynth.BACKING_BANK, status, data1, data2) },
        // The looper and a song's accompaniment would only get in each other's way.
        beforeStart = { looper.stop() },
        outputLatencyMillis = { _outputLatencyMillis.value },
        loadBest = { key -> scores.getInt(PlayerRoster.scoreKey(_players.value.currentId, key), -1).takeIf { it >= 0 } },
        saveBest = { key, percent -> scores.edit { putInt(PlayerRoster.scoreKey(_players.value.currentId, key), percent) } },
    )

    /** Makes it the turn of player [id] ([PlayerRoster.EVERYONE_ID] for no one in particular). */
    fun selectPlayer(id: Int) = updatePlayers(_players.value.select(id))

    /** Adds a player and makes it their turn. Returns why not, in words, if it could not be done. */
    fun addPlayer(name: String): String? = when (val change = _players.value.add(name)) {
        is PlayerRoster.Change.Done -> {
            clearScores(change.player.id) // an id is used again after its player is removed
            updatePlayers(change.roster)
            null
        }
        is PlayerRoster.Change.Refused -> change.reason
    }

    /** Returns why not, in words, if it could not be done. */
    fun renamePlayer(id: Int, name: String): String? = when (val change = _players.value.rename(id, name)) {
        is PlayerRoster.Change.Done -> {
            updatePlayers(change.roster)
            null
        }
        is PlayerRoster.Change.Refused -> change.reason
    }

    /** Takes a player off the list and deletes their best scores. */
    fun removePlayer(id: Int) {
        if (_players.value.players.none { it.id == id }) return
        Log.i(TAG, "removing player $id and their scores")
        clearScores(id)
        updatePlayers(_players.value.remove(id))
    }

    private fun updatePlayers(roster: PlayerRoster) {
        val before = _players.value
        if (roster == before) return
        _players.value = roster
        prefs.edit { putString(KEY_PLAYERS, PlayerCodec.encode(roster)) }
        if (roster.currentId != before.currentId) learn.onPlayerChanged()
    }

    private fun clearScores(playerId: Int) {
        val prefix = PlayerRoster.scorePrefix(playerId)
        val keys = scores.all.keys.filter { it.startsWith(prefix) }
        if (keys.isNotEmpty()) scores.edit { keys.forEach { remove(it) } }
    }

    /**
     * Writes the current loop as a MIDI file to the place the user picked. The file
     * picker has already made an empty file there, so if nothing can be written that
     * file is removed again rather than left behind.
     */
    fun exportLoopMidi(uri: Uri) {
        val bytes = looper.midiFile() // null, with a notice, if the loop has nothing to hear
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                val resolver = application.contentResolver
                val ok = bytes != null && runCatching {
                    // "wt" empties a file that was chosen to be replaced; a few storage
                    // providers only know plain "w".
                    val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: resolver.openOutputStream(uri, "w")
                    stream?.use { it.write(bytes) } != null
                }.getOrDefault(false)
                if (!ok) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                ok
            }
            if (bytes != null) looper.onMidiFileWritten(written)
        }
    }

    /**
     * Reads a file the user picked and offers it to the learn mode as a song to import.
     * Reading happens off the main thread; the result opens the import dialog.
     */
    fun importMidi(uri: Uri) {
        scope.launch {
            val (name, file) = withContext(Dispatchers.IO) {
                val resolver = application.contentResolver
                val name = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull() ?: "Imported song"
                val bytes = runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        // Read at most one byte over the limit, to tell "too big" from "fits".
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (out.size() <= MAX_IMPORT_BYTES) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
                // A MIDI file of a song is a few tens of kilobytes; anything huge is something else.
                val file = bytes?.takeIf { it.size <= MAX_IMPORT_BYTES }?.let(MidiFileReader::read)
                name to file
            }
            learn.offerImport(name, file)
        }
    }

    /** When the octave was last requested; rate-limits re-reads. Any thread. */
    @Volatile
    private var lastOctaveRequestAt = 0L

    // Bluetooth bookkeeping; main thread only.
    private var rememberedAddress: String? = prefs.getString(KEY_BT_ADDRESS, null)
    private var connectScreenOpen = false
    private var previousLink: Link = Link.None
    private var autoConnectNotBefore = 0L
    private var autoConnectRetry: Job? = null

    /** Number of activity instances currently started. Main thread only. */
    private var startedActivities = 0

    /** True while at least one activity is visible. Volatile: read on the MIDI thread. */
    @Volatile
    private var foreground = false

    @Volatile
    private var engineStarting = false

    /** MIDI thread only. */
    private var lastOrphanPressureLog = 0L

    /** Sample rate and burst size of the stream the latency figure belongs to. Poll loop only. */
    private var lastStreamShape = 0 to 0

    /** Channel that notes are currently sent on. Volatile: read on the MIDI thread. */
    @Volatile
    private var channel = Instruments.channelFor(_instrumentId.value)

    init {
        midi.start()
        startEngine()
        if (_developerMode.value) registerDebugBridge()
        scope.launch { midi.state.collect { onLinkChanged(it) } }
        scope.launch { scanner.devices.collect { onScanResults(it) } }
        scope.launch { scanner.scanning.collect { on -> _bluetooth.update { it.copy(scanning = on) } } }
        scope.launch { scanner.error.collect { e -> _bluetooth.update { it.copy(scanError = e) } } }
    }

    /** Starts the audio engine; also the Retry action after a failure. */
    fun startEngine() {
        if (_engineState.value == EngineState.Ready || engineStarting) return
        engineStarting = true
        _engineState.value = EngineState.Loading
        scope.launch(Dispatchers.IO) {
            val state = try {
                val soundFont = SoundFontInstaller.ensureInstalled(application)
                var started = false
                // Opening the audio stream has been seen to fail once straight after boot,
                // so try a few times before reporting failure.
                for (attempt in 1..START_ATTEMPTS) {
                    started = NativeSynth.start(soundFont.path)
                    if (started) break
                    Log.w(TAG, "audio engine start attempt $attempt of $START_ATTEMPTS failed")
                    delay(START_RETRY_DELAY_MS)
                }
                if (started) EngineState.Ready
                else EngineState.Failed("The audio output could not be opened.")
            } catch (e: Exception) {
                Log.e(TAG, "engine start failed", e)
                EngineState.Failed(e.message ?: e.javaClass.simpleName)
            }
            // The loop that was open last time, read here because this is off the main thread.
            val autosaved = if (state == EngineState.Ready) loopStore.readAutosave() else null
            val restoredLoop = autosaved?.let(LoopCodec::decode)
            val restoredName = autosaved?.let(LoopCodec::nameIn)
            // Publish on the main thread, where foreground changes also happen, so the
            // stream cannot be left running after the app has gone to the background.
            withContext(Dispatchers.Main) {
                engineStarting = false
                _engineState.value = state
                if (state == EngineState.Ready) {
                    sendProgram(_instrumentId.value)
                    NativeSynth.setPlaying(foreground)
                    looper.onEngineReady(NativeSynth.stats()[0], restoredLoop, restoredName)
                }
            }
            if (state == EngineState.Ready) pollDiagnostics()
        }
    }

    /** Call from Activity.onStart. Counted, because two instances can overlap. */
    fun onActivityStarted() {
        startedActivities++
        updateForeground()
    }

    /** Call from Activity.onStop. */
    fun onActivityStopped() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        updateForeground()
    }

    private fun updateForeground() {
        val isForeground = startedActivities > 0
        if (isForeground == foreground) return
        foreground = isForeground
        if (_engineState.value == EngineState.Ready) {
            if (!isForeground) {
                looper.stop() // audio stops in the background, so the loop cannot keep time
                learn.stop()  // and clear the keyboard's lights
                silence()
            }
            NativeSynth.setPlaying(isForeground)
        }
        refreshBluetooth()
        // The octave buttons may have been pressed while the app was away.
        if (isForeground) refreshKeyboardSettings()
    }

    fun selectInstrument(id: Int) {
        if (Instruments.byId(id) == null || id == _instrumentId.value) return
        silence()
        _instrumentId.value = id
        channel = Instruments.channelFor(id)
        sendProgram(id)
        prefs.edit { putInt(KEY_INSTRUMENT, id) }
    }

    fun toggleFavourite(id: Int) {
        _favourites.update { if (id in it) it - id else it + id }
        prefs.edit { putStringSet(KEY_FAVOURITES, _favourites.value.map(Int::toString).toSet()) }
    }

    fun selectVelocityCurve(curve: VelocityCurve) {
        _velocityCurve.value = curve
        prefs.edit { putString(KEY_VELOCITY_CURVE, curve.name) }
    }

    /**
     * Moves the playing range by octaves. With a keyboard whose settings are known this
     * changes the keyboard's own octave (like its octave buttons) and the screen follows;
     * otherwise it only moves the on-screen keyboard.
     */
    fun shiftOctave(delta: Int) {
        val octave = _keyboard.value.setting(LumiProtocol.ITEM_OCTAVE)
        if (_keyboard.value.status == KeyboardInfo.Status.READY && octave != null) {
            setKeyboardSetting(LumiProtocol.ITEM_OCTAVE, octave.value + delta)
            return
        }
        val shifted = (_baseNote.value + 12 * delta)
            .coerceIn(KeyboardLayout.MIN_BASE_NOTE, KeyboardLayout.MAX_BASE_NOTE)
        setBaseNote(shifted)
    }

    fun noteOn(note: Int, velocity: Int = TOUCH_VELOCITY) {
        if (note !in 0..127) return
        NativeSynth.sendMidi(0x90 or channel, note, velocity.coerceIn(1, 127))
        _pressedNotes.update { it + note }
        // The learn mode lives on the main thread; this may be the MIDI thread.
        scope.launch { learn.onKeyDown(note) }
    }

    fun noteOff(note: Int) {
        if (note !in 0..127) return
        NativeSynth.sendMidi(0x80 or channel, note, 0)
        _pressedNotes.update { it - note }
        scope.launch { learn.onKeyUp(note) }
    }

    // ---- Bluetooth -------------------------------------------------------------------------

    /** The Connect dialog scans while it is open, even with no remembered keyboard. */
    fun setConnectScreenOpen(open: Boolean) {
        connectScreenOpen = open
        refreshBluetooth()
    }

    fun onBluetoothPermissionResult(granted: Boolean) {
        _bluetooth.update { it.copy(permissionDenied = !granted) }
        refreshBluetooth()
    }

    /** Connects to a keyboard the user picked from the scan list. */
    fun connectBluetooth(found: BluetoothMidiScanner.Found) {
        midi.connectBluetooth(found.device, found.name)
    }

    /** Drops the current link (or attempt) and stops reconnecting to it automatically. */
    fun disconnectKeyboard() {
        rememberedAddress = null
        prefs.edit { remove(KEY_BT_ADDRESS).remove(KEY_BT_NAME) }
        _bluetooth.update { it.copy(rememberedName = null) }
        midi.disconnect()
        refreshBluetooth()
    }

    /**
     * Re-reads permission and adapter state and starts or stops scanning to match.
     * Scans only in the foreground, with nothing connected, and when there is a
     * reason to: the dialog is open or a remembered keyboard may reappear.
     */
    fun refreshBluetooth() {
        val granted = BluetoothPermissions.granted(application)
        val enabled = scanner.isEnabled
        _bluetooth.update { it.copy(permissionGranted = granted, enabled = enabled) }

        val wanted = foreground && _bluetooth.value.available && granted && enabled &&
            midi.state.value is Link.None && (connectScreenOpen || rememberedAddress != null)
        if (wanted) scanner.start() else scanner.stop()
    }

    private fun onLinkChanged(link: Link) {
        if (link is Link.Connected && link.bluetoothAddress != null) {
            rememberedAddress = link.bluetoothAddress
            prefs.edit { putString(KEY_BT_ADDRESS, link.bluetoothAddress).putString(KEY_BT_NAME, link.name) }
            _bluetooth.update { it.copy(rememberedName = link.name) }
        }
        if (link is Link.None && previousLink !is Link.None) {
            // A drop or a failed attempt: leave a gap before reconnecting automatically.
            autoConnectNotBefore = SystemClock.elapsedRealtime() + AUTO_CONNECT_BACKOFF_MS
            // Keys held when the keyboard vanished would otherwise sound for ever.
            if (previousLink is Link.Connected) silence()
        }
        if (link is Link.Connected && previousLink !is Link.Connected) startKeyboardHandshake()
        if (link !is Link.Connected) {
            handshake?.cancel()
            _keyboard.value = KeyboardInfo()
            litKeys = emptySet() // whatever was lit went with the connection
            modeBeforeLearn = null
        }
        previousLink = link
        refreshBluetooth()
    }

    // ---- Keyboard settings -----------------------------------------------------------------

    /**
     * Asks the keyboard to identify itself, retrying because over Bluetooth the link is
     * reported connected slightly before it can carry data.
     */
    private fun startKeyboardHandshake() {
        handshake?.cancel()
        _keyboard.value = KeyboardInfo(status = KeyboardInfo.Status.READING)
        handshake = scope.launch {
            repeat(HANDSHAKE_ATTEMPTS) {
                delay(if (it == 0) HANDSHAKE_FIRST_DELAY_MS else HANDSHAKE_RETRY_MS)
                if (_keyboard.value.deviceIndex != null) return@launch
                midi.send(LumiProtocol.topologyRequest())
            }
            delay(HANDSHAKE_RETRY_MS)
            if (_keyboard.value.deviceIndex == null) {
                val link = midi.state.value
                if (link is Link.Connected && link.bluetoothAddress != null && !reconnectedAfterSilence) {
                    // Seen once: a Bluetooth link made straight after the app had been killed
                    // reported "connected" but nothing came back from the keyboard, and a
                    // fresh connection cured it. So drop the link once; the remembered
                    // keyboard is then found and connected again by the scan.
                    reconnectedAfterSilence = true
                    Log.w(TAG, "keyboard did not answer; dropping the Bluetooth link to connect afresh")
                    midi.disconnect()
                } else {
                    Log.w(TAG, "keyboard did not answer the topology request")
                    _keyboard.update { it.copy(status = KeyboardInfo.Status.NO_ANSWER) }
                }
            }
        }
    }

    /** Re-reads the settings the app shows. The keyboard does not report changes by itself. */
    fun refreshKeyboardSettings() {
        val index = _keyboard.value.deviceIndex ?: return
        lastOctaveRequestAt = SystemClock.elapsedRealtime()
        for (item in SETTINGS_ITEMS) midi.send(LumiProtocol.requestConfig(index, item))
    }

    /**
     * Changes a setting on the keyboard. Apart from the active mode these are stored
     * by the keyboard and survive a power cycle. The keyboard sends no confirmation,
     * so the value is read back afterwards.
     */
    fun setKeyboardSetting(item: Int, value: Int) {
        val info = _keyboard.value
        val index = info.deviceIndex ?: return
        val known = info.setting(item)
        val target = if (known != null) value.coerceIn(known.min, known.max) else value
        if (known != null && target == known.value) return
        Log.i(TAG, "keyboard setting $item -> $target")
        midi.send(LumiProtocol.setConfig(index, item, target))
        midi.send(LumiProtocol.requestConfig(index, item))
        if (known != null) applyKeyboardSetting(item, known.copy(value = target)) // until the read-back arrives
    }

    private fun applyKeyboardSetting(item: Int, setting: KeyboardInfo.Setting) {
        _keyboard.update { it.copy(config = it.config + (item to setting)) }
        if (item == LumiProtocol.ITEM_OCTAVE) {
            setBaseNote(
                LumiProtocol.baseNoteForOctave(setting.value)
                    .coerceIn(KeyboardLayout.MIN_BASE_NOTE, KeyboardLayout.MAX_BASE_NOTE),
            )
        }
    }

    private fun onKeyboardMessages(messages: List<DeviceMessage>) {
        for (message in messages) {
            when (message) {
                is DeviceMessage.Device -> {
                    val first = _keyboard.value.deviceIndex == null
                    _keyboard.update {
                        it.copy(
                            status = KeyboardInfo.Status.READY,
                            deviceIndex = message.index,
                            batteryPercent = message.batteryPercent,
                            charging = message.charging,
                        )
                    }
                    if (first) {
                        reconnectedAfterSilence = false
                        Log.i(TAG, "keyboard answered: device index ${message.index}, battery ${message.batteryPercent}%")
                        refreshKeyboardSettings()
                    }
                }
                is DeviceMessage.Version -> _keyboard.update { it.copy(firmware = message.text) }
                is DeviceMessage.Config ->
                    applyKeyboardSetting(message.item, KeyboardInfo.Setting(message.value, message.min, message.max))
                is DeviceMessage.PacketAck, is DeviceMessage.Button -> Unit
            }
        }
    }

    /** Asks for the octave again if that has not been done recently. Any thread. */
    private fun refreshOctave(minIntervalMs: Long) {
        val index = _keyboard.value.deviceIndex ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOctaveRequestAt < minIntervalMs) return
        lastOctaveRequestAt = now
        midi.send(LumiProtocol.requestConfig(index, LumiProtocol.ITEM_OCTAVE))
    }

    private fun onScanResults(devices: List<BluetoothMidiScanner.Found>) {
        _bluetooth.update { it.copy(devices = devices) }
        tryAutoConnect()
    }

    /** Connects to the remembered keyboard if the scan can currently see it. */
    private fun tryAutoConnect() {
        val address = rememberedAddress ?: return
        if (midi.state.value !is Link.None) return
        val match = scanner.devices.value.firstOrNull { it.address == address } ?: return

        val wait = autoConnectNotBefore - SystemClock.elapsedRealtime()
        if (wait > 0) {
            // The scan only reports changes, so come back when the gap has passed.
            if (autoConnectRetry?.isActive != true) {
                autoConnectRetry = scope.launch {
                    delay(wait)
                    tryAutoConnect()
                }
            }
            return
        }
        Log.i(TAG, "remembered keyboard ${match.name} is in range; connecting")
        midi.connectBluetooth(match.device, match.name)
    }

    // ---- Debug bridge ----------------------------------------------------------------------

    /**
     * Developer mode in debuggable builds only: lets a developer send raw MIDI to the
     * keyboard from a computer, for protocol experiments such as key lighting:
     *
     *   adb shell am broadcast -p com.dhobson.lumi -a com.dhobson.lumi.DEBUG_SEND_MIDI --es hex "90 3C 40"
     *
     * Exported so the shell can reach it, which also means any app on the device
     * could; hence never in a release build.
     */
    private fun registerDebugBridge() {
        if (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (debugReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val hex = intent.getStringExtra("hex").orEmpty().filterNot { it.isWhitespace() }
                val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                if (hex.length % 2 != 0 || bytes.size != hex.length / 2 || bytes.isEmpty()) {
                    Log.w(TAG, "debug send: bad hex '$hex'")
                    return
                }
                // Optional: --ei gap N spaces the messages N ms apart (pacing experiments).
                val gap = intent.getIntExtra("gap", -1)
                val queued = if (gap >= 0) midi.send(bytes, gap.toLong()) else midi.send(bytes)
                Log.i(TAG, "debug send gap=$gap ${hexString(bytes)} -> ${if (queued) "queued" else "NO SEND PORT"}")
            }
        }
        ContextCompat.registerReceiver(
            application, receiver, IntentFilter(ACTION_DEBUG_SEND_MIDI), ContextCompat.RECEIVER_EXPORTED,
        )
        debugReceiver = receiver
    }

    private fun unregisterDebugBridge() {
        debugReceiver?.let { application.unregisterReceiver(it) }
        debugReceiver = null
    }

    private fun hexString(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    // ---- Hardware input --------------------------------------------------------------------

    /** SysEx from the keyboard (replies to topology and config requests), on the MIDI thread. */
    private fun onHardwareSysEx(message: ByteArray) {
        if (logKeys) Log.i(TAG, "sysex in (${message.size} bytes): ${hexString(message)}")
        val messages = LumiProtocol.decode(message)
        if (messages.isNotEmpty()) scope.launch { onKeyboardMessages(messages) }
    }

    /**
     * Messages from the hardware keyboard, on the MIDI reader thread. Everything is
     * folded onto the current channel so the instrument choice applies whatever
     * channel mode the keyboard is in.
     */
    private fun onHardwareMidi(status: Int, data1: Int, data2: Int) {
        // Audio is stopped in the background; queued notes would all sound on return.
        if (!foreground) return
        when (status and 0xF0) {
            0x90 -> if (data2 == 0) noteOff(data1) else {
                val played = _velocityCurve.value.apply(data2)
                if (logKeys) {
                    Log.d(TAG, "key down note=$data1 velocity=$data2 played=$played " +
                        "ch=${(status and 0x0F) + 1} dispatch=${midi.lastDispatchDelayNanos / 1000}us")
                }
                followKeyboardRange(data1)
                noteOn(data1, played)
            }
            0x80 -> {
                if (logKeys) Log.d(TAG, "key up note=$data1 release=$data2")
                noteOff(data1)
            }
            0xA0 -> if (logKeys && data1 !in _pressedNotes.value) {
                // Pressure on a key that never sent a key-down: a touch too light to trigger.
                val now = SystemClock.elapsedRealtime()
                if (now - lastOrphanPressureLog > 250) {
                    lastOrphanPressureLog = now
                    Log.d(TAG, "pressure without key down note=$data1 pressure=$data2")
                }
            }
            0xB0 -> if (data1 in FORWARDED_CONTROLLERS) NativeSynth.sendMidi(0xB0 or channel, data1, data2)
            0xE0 -> NativeSynth.sendMidi(0xE0 or channel, data1, data2)
            // Key and channel pressure are dropped for now: the keyboard streams them
            // continuously and they would add vibrato to every sustained note.
            else -> Unit
        }
    }

    /** Keeps the on-screen keyboard showing the octaves the hardware keyboard is set to. */
    private fun followKeyboardRange(note: Int) {
        val base = _baseNote.value
        val adjusted = KeyboardLayout.baseNoteContaining(base, note)
        if (adjusted != base) {
            // The keyboard's octave must have changed: move now, and ask it for the real value.
            setBaseNote(adjusted)
            refreshOctave(minIntervalMs = OCTAVE_RECHECK_AFTER_SURPRISE_MS)
        } else {
            // A one-octave change leaves half the keys inside the old range, so also
            // re-check now and then while the keyboard is being played.
            refreshOctave(minIntervalMs = OCTAVE_RECHECK_WHILE_PLAYING_MS)
        }
    }

    private fun setBaseNote(base: Int) {
        if (base == _baseNote.value) return
        _baseNote.value = base
        prefs.edit { putInt(KEY_BASE_NOTE, base) }
        scope.launch { learn.onBaseNoteChanged() }
    }

    private fun sendProgram(instrumentId: Int) {
        if (instrumentId != Instruments.DRUM_KIT_ID) {
            NativeSynth.sendMidi(0xC0 or Instruments.MELODIC_CHANNEL, instrumentId, 0)
        }
    }

    /** Releases everything that is sounding on the current channel. */
    private fun silence() {
        NativeSynth.sendMidi(0xB0 or channel, CC_SUSTAIN, 0)
        NativeSynth.sendMidi(0xB0 or channel, CC_ALL_NOTES_OFF, 0)
        _pressedNotes.value = emptySet()
    }

    private suspend fun pollDiagnostics() {
        while (true) {
            if (!foreground) { // nothing is on screen to show it
                delay(500)
                continue
            }
            val stats = NativeSynth.stats()
            val (rate, burst, buffer, xruns, exclusive) = stats
            val dropped = stats[5]
            // Android only reports the delay some of the time, so keep the last known
            // figure until the stream itself changes (a different output device).
            val streamShape = rate to burst
            if (streamShape != lastStreamShape) {
                lastStreamShape = streamShape
                _outputLatencyMillis.value = -1
            }
            if (stats[6] >= 0) _outputLatencyMillis.value = stats[6]
            val latency = _outputLatencyMillis.value
            val peak = NativeSynth.takePeak()
            val audible = peak > SILENCE_PEAK
            val level = if (audible) "%.0f dB".format(20 * log10(peak)) else "silent"
            _diagnostics.value = buildString {
                if (latency >= 0) append("out $latency ms  ")
                append("xruns $xruns  ")
                if (dropped > 0) append("dropped $dropped  ")
                append("level $level  $rate Hz  burst $burst  buffer $buffer  ")
                append(if (exclusive == 1) "exclusive" else "shared")
            }
            if (audible) Log.d(TAG, "output level $level (peak $peak)")
            delay(500)
        }
    }

    private companion object {
        const val TAG = "LumiApp"
        const val ACTION_DEBUG_SEND_MIDI = "com.dhobson.lumi.DEBUG_SEND_MIDI"
        const val KEY_INSTRUMENT = "instrument"
        const val KEY_FAVOURITES = "favourites"
        const val KEY_BASE_NOTE = "baseNote"
        const val KEY_BT_ADDRESS = "bluetoothAddress"
        const val KEY_BT_NAME = "bluetoothName"
        const val KEY_VELOCITY_CURVE = "velocityCurve"
        const val KEY_SHOW_AUDIO_DETAILS = "showAudioDetails"
        const val KEY_DEVELOPER_MODE = "developerMode"
        const val KEY_PLAYERS = "players"
        const val LIGHT_VELOCITY: Byte = 64
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
        const val DEFAULT_BASE_NOTE = 48 // C3; follows the hardware keyboard once it is played
        const val TOUCH_VELOCITY = 100
        const val START_ATTEMPTS = 3
        const val START_RETRY_DELAY_MS = 700L
        const val AUTO_CONNECT_BACKOFF_MS = 10_000L
        const val HANDSHAKE_ATTEMPTS = 5
        const val HANDSHAKE_FIRST_DELAY_MS = 700L
        const val HANDSHAKE_RETRY_MS = 1_500L
        const val OCTAVE_RECHECK_AFTER_SURPRISE_MS = 500L
        const val OCTAVE_RECHECK_WHILE_PLAYING_MS = 10_000L

        /** Settings read from the keyboard: octave, mode, brightness, sensitivity, and each mode's scheme. */
        val SETTINGS_ITEMS = listOf(
            LumiProtocol.ITEM_OCTAVE, LumiProtocol.ITEM_MODE, LumiProtocol.ITEM_BRIGHTNESS,
            LumiProtocol.ITEM_STRIKE_SENSITIVITY,
        ) + (0..3).map(LumiProtocol::colourSchemeItem)
        const val SILENCE_PEAK = 1e-5f // -100 dB; reverb tails decay towards zero for a long time
        const val CC_SUSTAIN = 64
        const val CC_ALL_NOTES_OFF = 123
        val FORWARDED_CONTROLLERS = setOf(1, CC_SUSTAIN, 120, CC_ALL_NOTES_OFF)
    }
}
