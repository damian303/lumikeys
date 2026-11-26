package com.dhobson.lumi.midi

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the link to one hardware MIDI keyboard and forwards its messages.
 *
 * USB keyboards are picked up automatically from the system's device list. A
 * Bluetooth keyboard is only ever opened with [connectBluetooth], after a scan has
 * seen it, and is only reported as connected once the radio link really exists:
 * the system's MIDI service can list a Bluetooth keyboard, and hand out a working-
 * looking device for it, when there is no link behind it (seen on a Galaxy Tab A8).
 *
 * Note the platform's naming: we read notes from the device's *output* port.
 * All state is confined to the main thread; [onShortMessage] is called on the
 * platform's MIDI reader thread.
 */
class MidiConnection(
    context: Context,
    onShortMessage: (status: Int, data1: Int, data2: Int) -> Unit,
    onSysEx: (ByteArray) -> Unit = {},
) {
    enum class Transport(val label: String) { USB("USB"), BLUETOOTH("Bluetooth"), OTHER("MIDI") }

    sealed interface Link {
        data object None : Link
        data class Connecting(val name: String) : Link
        data class Connected(
            val name: String,
            val transport: Transport,
            /** Set when this app opened the device over Bluetooth itself. */
            val bluetoothAddress: String? = null,
        ) : Link
    }

    private val appContext = context.applicationContext
    private val midiManager: MidiManager? =
        if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            appContext.getSystemService(MidiManager::class.java)
        } else {
            null
        }
    private val bluetoothManager: BluetoothManager? = appContext.getSystemService(BluetoothManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val parser = MidiParser(onShortMessage, onSysEx)

    private val _state = MutableStateFlow<Link>(Link.None)
    val state: StateFlow<Link> = _state

    /** Why the last connection attempt failed or the link dropped; cleared on the next attempt. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** False on devices without the platform MIDI service. */
    val isSupported: Boolean get() = midiManager != null

    private var started = false
    private var opening = false
    private var device: MidiDevice? = null
    private var port: MidiOutputPort? = null

    /** Port for sending to the keyboard (key lights, settings). Volatile: used by the send thread. */
    @Volatile
    private var sendPort: MidiInputPort? = null

    /** Sends can block, so they run in order on their own thread. */
    private val sendExecutor = Executors.newSingleThreadExecutor { Thread(it, "LumiMidiSend") }
    private var deviceId = -1

    /** Bumped whenever an attempt starts or the link is closed, so late callbacks can be discarded. */
    private var generation = 0

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            if (timestamp != 0L) lastDispatchDelayNanos = System.nanoTime() - timestamp
            parser.feed(msg, offset, count)
        }
    }

    /**
     * Time between the platform's timestamp on the latest MIDI data and our receiving
     * it. Measured over Bluetooth it ranged from under 1 ms to about 21 ms. What the
     * Bluetooth timestamp represents (key press or arrival at the phone) is not
     * established, so treat this as a lower bound on the keyboard-to-app delay.
     */
    @Volatile
    var lastDispatchDelayNanos = 0L
        private set

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            mainHandler.post { tryOpen(info) }
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            mainHandler.post {
                if (info.id == deviceId) {
                    val name = (_state.value as? Link.Connected)?.name ?: info.displayName()
                    Log.i(TAG, "device removed: $name")
                    closeDevice()
                    _error.value = "$name disconnected."
                    openFirstAvailable()
                }
            }
        }
    }

    /** Call on the main thread. */
    fun start() {
        val manager = midiManager ?: return
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.registerDeviceCallback(
                MidiManager.TRANSPORT_MIDI_BYTE_STREAM,
                appContext.mainExecutor,
                deviceCallback,
            )
        } else {
            @Suppress("DEPRECATION")
            manager.registerDeviceCallback(deviceCallback, mainHandler)
        }
        openFirstAvailable()
    }

    /** Call on the main thread. */
    fun stop() {
        if (!started) return
        started = false
        midiManager?.unregisterDeviceCallback(deviceCallback)
        closeDevice()
    }

    /**
     * Opens a scanned Bluetooth LE MIDI keyboard, replacing any current link. The
     * caller must hold the Bluetooth permissions. Result arrives via [state] / [error].
     */
    fun connectBluetooth(bluetoothDevice: BluetoothDevice, name: String) {
        val manager = midiManager ?: return
        if (!started) return
        closeDevice()
        val attempt = ++generation
        opening = true
        _error.value = null
        _state.value = Link.Connecting(name)
        Log.i(TAG, "connecting over Bluetooth: $name")

        try {
            manager.openBluetoothDevice(bluetoothDevice, { opened ->
                if (attempt != generation) { // timed out, cancelled or superseded
                    opened?.close()
                    return@openBluetoothDevice
                }
                opening = false
                if (opened == null || !attach(opened, name, Transport.BLUETOOTH, bluetoothDevice.address)) {
                    opened?.close()
                    fail("Could not connect to $name.")
                } else {
                    watchRadioLink(bluetoothDevice, name)
                }
            }, mainHandler)
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth connect not permitted", e)
            opening = false
            fail("Bluetooth permission is missing.")
            return
        }

        // The platform is not guaranteed to call back when the keyboard is out of range.
        mainHandler.postDelayed({
            if (attempt == generation && opening) {
                generation++
                opening = false
                fail("$name did not respond. Is it switched on and nearby?")
            }
        }, BLUETOOTH_CONNECT_TIMEOUT_MS)
    }

    /**
     * Whether the tablet's radio is really connected to [bluetoothDevice], whichever
     * process holds the connection. Null if that cannot be found out.
     */
    private fun radioLinked(bluetoothDevice: BluetoothDevice): Boolean? = try {
        bluetoothManager?.getConnectionState(bluetoothDevice, BluetoothProfile.GATT)
            ?.let { it == BluetoothProfile.STATE_CONNECTED }
    } catch (e: SecurityException) {
        null
    }

    /**
     * Follows the radio link of a Bluetooth keyboard that has just been opened. The
     * keyboard is shown as connected only once the link is up; if it never comes up,
     * or later goes away, the device is closed. The system does not reliably report
     * either case itself.
     */
    private fun watchRadioLink(bluetoothDevice: BluetoothDevice, name: String) {
        val watched = generation
        val startedAt = SystemClock.elapsedRealtime()
        var everLinked = false
        var missed = 0
        val check = object : Runnable {
            override fun run() {
                if (watched != generation) return // closed or replaced meanwhile
                when (radioLinked(bluetoothDevice)) {
                    true -> {
                        missed = 0
                        if (!everLinked) {
                            everLinked = true
                            Log.i(TAG, "radio link up after ${SystemClock.elapsedRealtime() - startedAt} ms")
                            _state.value = Link.Connected(name, Transport.BLUETOOTH, bluetoothDevice.address)
                        }
                    }
                    false -> if (everLinked) {
                        if (++missed >= LINK_LOST_CHECKS) {
                            Log.i(TAG, "radio link to $name lost")
                            closeDevice()
                            _error.value = "$name disconnected."
                            return
                        }
                    } else if (SystemClock.elapsedRealtime() - startedAt > LINK_UP_TIMEOUT_MS) {
                        Log.w(TAG, "the system opened $name but no radio link followed")
                        closeDevice()
                        fail("Could not reach $name. Is it switched on and nearby?")
                        return
                    }
                    null -> if (!everLinked) { // cannot tell: take the system's word for it, as before
                        everLinked = true
                        _state.value = Link.Connected(name, Transport.BLUETOOTH, bluetoothDevice.address)
                    }
                }
                mainHandler.postDelayed(this, LINK_CHECK_INTERVAL_MS)
            }
        }
        check.run()
    }

    /** Whether messages can currently be sent to the keyboard. */
    val canSend: Boolean get() = sendPort != null

    /**
     * Queues raw MIDI bytes (complete messages) for the keyboard. Returns false if
     * there is nowhere to send them. Safe from any thread; delivery is asynchronous.
     *
     * Each message is sent on its own, at least [gapMillis] after the previous one,
     * because the LUMI drops messages that arrive packed together over Bluetooth
     * (see [DEFAULT_SEND_GAP_MS]).
     */
    fun send(bytes: ByteArray, gapMillis: Long = sendGapMillis): Boolean {
        val target = sendPort ?: return false
        val messages = MidiMessages.split(bytes)
        sendExecutor.execute {
            for (message in messages) {
                val wait = lastSendAt + gapMillis - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
                try {
                    target.send(message, 0, message.size)
                } catch (e: IOException) {
                    Log.w(TAG, "send to keyboard failed", e)
                    return@execute
                }
                lastSendAt = SystemClock.elapsedRealtime()
            }
        }
        return true
    }

    /** Default spacing between messages to the keyboard. */
    @Volatile
    var sendGapMillis = DEFAULT_SEND_GAP_MS

    /** Send thread only. */
    private var lastSendAt = 0L

    /** Closes the current link (or cancels a connection attempt). */
    fun disconnect() {
        closeDevice()
        _error.value = null
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        _state.value = Link.None
        _error.value = message
    }

    private fun openFirstAvailable() {
        val manager = midiManager ?: return
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)
        } else {
            @Suppress("DEPRECATION")
            manager.devices.toList()
        }
        devices.forEach(::tryOpen)
    }

    /** Opens a device from the system's list unless something is already open or opening. */
    private fun tryOpen(info: MidiDeviceInfo) {
        val manager = midiManager ?: return
        if (!started || opening || device != null) return
        if (info.type == MidiDeviceInfo.TYPE_VIRTUAL || info.outputPortCount == 0) return
        if (info.type == MidiDeviceInfo.TYPE_BLUETOOTH) {
            // Not taken on trust: this can be a leftover with no radio link behind it.
            Log.i(TAG, "not opening ${info.displayName()} from the system's list; Bluetooth goes through the scan")
            return
        }

        val attempt = ++generation
        opening = true
        manager.openDevice(info, { opened ->
            if (attempt != generation) {
                opened?.close()
                return@openDevice
            }
            opening = false
            if (opened == null || !attach(opened, info.displayName(), info.transport(), null)) {
                opened?.close()
                Log.w(TAG, "could not open ${info.displayName()}")
            }
        }, mainHandler)
    }

    /** Starts reading from an opened device. Returns false if it has no readable port. */
    private fun attach(opened: MidiDevice, name: String, transport: Transport, address: String?): Boolean {
        if (opened.info.outputPortCount == 0) return false
        val outputPort = opened.openOutputPort(0) ?: return false
        outputPort.connect(receiver)
        device = opened
        port = outputPort
        // The platform calls the port we write to an "input" port. Only one client can
        // hold it, so this is null if another app has it open.
        sendPort = if (opened.info.inputPortCount > 0) opened.openInputPort(0) else null
        if (sendPort == null) Log.w(TAG, "$name: no port available for sending to the keyboard")
        deviceId = opened.info.id
        _error.value = null
        if (transport == Transport.BLUETOOTH) {
            Log.i(TAG, "opened $name over Bluetooth; waiting for the radio link") // see watchRadioLink
        } else {
            _state.value = Link.Connected(name, transport, address)
            Log.i(TAG, "connected: $name (${transport.label})")
        }
        return true
    }

    private fun closeDevice() {
        generation++
        opening = false
        runCatching { port?.disconnect(receiver) }
        runCatching { port?.close() }
        runCatching { sendPort?.close() }
        sendPort = null
        runCatching { device?.close() }
        port = null
        device = null
        deviceId = -1
        _state.value = Link.None
    }

    private fun MidiDeviceInfo.displayName(): String =
        properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI device"

    private fun MidiDeviceInfo.transport(): Transport = when (type) {
        MidiDeviceInfo.TYPE_USB -> Transport.USB
        MidiDeviceInfo.TYPE_BLUETOOTH -> Transport.BLUETOOTH
        else -> Transport.OTHER
    }

    private companion object {
        const val TAG = "LumiMidi"
        const val BLUETOOTH_CONNECT_TIMEOUT_MS = 20_000L
        const val LINK_CHECK_INTERVAL_MS = 500L
        const val LINK_UP_TIMEOUT_MS = 8_000L
        const val LINK_LOST_CHECKS = 3
        /**
         * Pacing test over Bluetooth (connection interval 15 ms), six note-ons: with no
         * gap 2 of 6 lit; with 20 ms and with 50 ms all 6 lit. 30 ms leaves a margin.
         */
        const val DEFAULT_SEND_GAP_MS = 30L
    }
}
