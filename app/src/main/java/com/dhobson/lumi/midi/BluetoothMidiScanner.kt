package com.dhobson.lumi.midi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * Finds Bluetooth LE MIDI keyboards nearby. Callers must hold
 * [BluetoothPermissions.required] before calling [start]. Main thread only.
 */
class BluetoothMidiScanner(context: Context) {
    data class Found(
        val address: String,
        val name: String,
        val rssi: Int,
        val advertisesMidi: Boolean,
        val device: BluetoothDevice,
    )

    private val adapter: BluetoothAdapter? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        } else {
            null
        }

    /** False on devices without Bluetooth LE. */
    val isAvailable: Boolean get() = adapter != null

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    private val _devices = MutableStateFlow<List<Found>>(emptyList())
    val devices: StateFlow<List<Found>> = _devices

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var activeScanner: BluetoothLeScanner? = null
    private val found = LinkedHashMap<String, Found>()
    private val lastSeen = HashMap<String, Long>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach(::handleResult)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            activeScanner = null
            _scanning.value = false
            _error.value = "Bluetooth search failed (code $errorCode)."
        }
    }

    /** Starts scanning if not already. No-op when Bluetooth is off or unavailable. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (activeScanner != null) return
        val scanner = adapter?.bluetoothLeScanner ?: return // null while Bluetooth is off
        found.clear()
        lastSeen.clear()
        _devices.value = emptyList()
        _error.value = null
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            // Unfiltered on purpose: results are filtered in handleResult by service UUID or name.
            scanner.startScan(null, settings, callback)
            activeScanner = scanner
            _scanning.value = true
        } catch (e: SecurityException) {
            Log.w(TAG, "scan not permitted", e)
            _error.value = "Bluetooth permission is missing."
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val scanner = activeScanner ?: return
        activeScanner = null
        _scanning.value = false
        // Throws if Bluetooth was switched off or the permission revoked meanwhile.
        runCatching { scanner.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        // Any advert (from any device) is a chance to drop keyboards that have gone quiet.
        val now = SystemClock.elapsedRealtime()
        val stale = lastSeen.filterValues { now - it > STALE_AFTER_MS }.keys.toList()
        stale.forEach { found.remove(it); lastSeen.remove(it) }

        val record = result.scanRecord
        val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
        val serviceUuids = record?.serviceUuids.orEmpty().map { it.uuid.toString() }
        var changed = stale.isNotEmpty()

        val address = result.device.address
        // Once listed, a keyboard stays listed even if a later advert omits its name.
        if (address in found || BleMidi.shouldList(serviceUuids, name)) {
            lastSeen[address] = now
            val previous = found[address]
            val entry = Found(
                address = address,
                name = name ?: previous?.name ?: "MIDI keyboard",
                rssi = result.rssi,
                advertisesMidi = previous?.advertisesMidi == true ||
                    serviceUuids.any { it.equals(BleMidi.SERVICE_UUID, ignoreCase = true) },
                device = result.device,
            )
            // Adverts arrive many times a second; only republish on a meaningful change.
            if (previous == null || previous.name != entry.name ||
                previous.advertisesMidi != entry.advertisesMidi ||
                abs(previous.rssi - entry.rssi) >= RSSI_STEP
            ) {
                found[address] = entry
                changed = true
            }
        }
        if (changed) {
            _devices.value = found.values.sortedByDescending { it.rssi }
        }
    }

    private companion object {
        const val TAG = "LumiBle"
        const val STALE_AFTER_MS = 10_000L
        const val RSSI_STEP = 8
    }
}
