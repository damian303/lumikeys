package com.dhobson.lumi.midi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Bluetooth LE MIDI constants and the rule for which scan results to show. Pure Kotlin. */
object BleMidi {
    /** Service UUID from the MIDI over Bluetooth LE specification. */
    const val SERVICE_UUID = "03b80e5a-ede8-4b33-a751-6ce34ec4c700"

    private val brandWords = setOf("lumi", "roli", "seaboard", "lightpad")

    /**
     * Whether a device name looks like a ROLI keyboard. Expected (not yet confirmed
     * on hardware) to be of the form "LUMI Keys Block XXXX". Matches whole words
     * only, so "Caroline's iPhone" and "Aluminium" do not count.
     */
    fun isLikelyKeyboardName(name: String?): Boolean {
        val words = name?.lowercase()?.split(Regex("[^a-z0-9]+"))?.filter { it.isNotEmpty() } ?: return false
        return words.any { it in brandWords } || words.drop(1).contains("block")
    }

    /**
     * Whether to list a scanned device. It is not confirmed that the keyboard puts the
     * MIDI service UUID in its advertisement, so a ROLI-looking name is accepted too.
     */
    fun shouldList(advertisedServiceUuids: List<String>, name: String?): Boolean =
        advertisedServiceUuids.any { it.equals(SERVICE_UUID, ignoreCase = true) } ||
            isLikelyKeyboardName(name)
}

object BluetoothPermissions {
    /** Runtime permissions needed to scan for and connect to the keyboard. */
    val required: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context): Boolean =
        required.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}
