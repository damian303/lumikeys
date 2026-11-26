package com.dhobson.lumi.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dhobson.lumi.BluetoothUi
import com.dhobson.lumi.KeyboardInfo
import com.dhobson.lumi.Session
import com.dhobson.lumi.midi.BluetoothMidiScanner
import com.dhobson.lumi.midi.BluetoothPermissions
import com.dhobson.lumi.midi.LumiProtocol
import com.dhobson.lumi.midi.MidiConnection.Link
import com.dhobson.lumi.music.KeyboardLayout
import com.dhobson.lumi.music.VelocityCurve
import kotlin.math.roundToInt

/** Shows the current link and lets the user find and connect a Bluetooth keyboard. */
@Composable
fun ConnectDialog(session: Session, onDismiss: () -> Unit) {
    val link by session.connection.collectAsStateWithLifecycle()
    val error by session.connectionError.collectAsStateWithLifecycle()
    val bluetooth by session.bluetooth.collectAsStateWithLifecycle()
    val keyboard by session.keyboard.collectAsStateWithLifecycle()
    val showAudioDetails by session.showAudioDetails.collectAsStateWithLifecycle()
    val developerMode by session.developerMode.collectAsStateWithLifecycle()
    val velocityCurve by session.velocityCurve.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> session.onBluetoothPermissionResult(results.values.all { it }) }
    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { session.refreshBluetooth() }

    // Scanning runs while the dialog is open.
    DisposableEffect(Unit) {
        session.setConnectScreenOpen(true)
        onDispose { session.setConnectScreenOpen(false) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Connect keyboard") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                LinkStatus(link, onDisconnect = session::disconnectKeyboard)
                if (link is Link.Connected) {
                    KeyboardSettingsSection(keyboard, onSet = session::setKeyboardSetting)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (link is Link.None) {
                    Text(
                        "USB: plug the keyboard into this device with a USB-C cable. It connects by itself.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Bluetooth", style = MaterialTheme.typography.titleMedium)
                    BluetoothSection(
                        bluetooth = bluetooth,
                        onRequestPermission = { permissionLauncher.launch(BluetoothPermissions.required) },
                        onEnableBluetooth = {
                            enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        },
                        onConnect = session::connectBluetooth,
                    )
                }

                // How hard the real keys must be pressed to be heard. Kept in the app, not the keyboard.
                Text("Touch", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VelocityCurve.entries.forEach { curve ->
                        FilterChip(
                            selected = curve == velocityCurve,
                            onClick = { session.selectVelocityCurve(curve) },
                            label = { Text(curve.label, maxLines = 1, softWrap = false) },
                            modifier = Modifier.semantics { contentDescription = "Touch ${curve.label}" },
                        )
                    }
                }
                Text(
                    "Light makes gentle presses easy to hear. \"As sent\" plays exactly as hard as the keyboard reports.",
                    style = MaterialTheme.typography.bodySmall,
                )

                // Not about the keyboard, but this dialog is the app's only settings page.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Show audio details", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Output delay, dropouts and level, under the instrument name.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = showAudioDetails,
                        onCheckedChange = session::setShowAudioDetails,
                        modifier = Modifier.semantics { contentDescription = "Show audio details" },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Developer mode", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "For fault-finding with a computer attached. Leave off otherwise.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = developerMode,
                        onCheckedChange = session::setDeveloperMode,
                        modifier = Modifier.semantics { contentDescription = "Developer mode" },
                    )
                }
            }
        },
    )
}

@Composable
private fun LinkStatus(link: Link, onDisconnect: () -> Unit) {
    when (link) {
        Link.None -> Unit
        is Link.Connecting -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Connecting to ${link.name}", modifier = Modifier.weight(1f))
            TextButton(onClick = onDisconnect) { Text("Cancel") }
        }
        is Link.Connected -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Connected: ${link.name} (${link.transport.label})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (link.bluetoothAddress != null) {
                Text(
                    "It reconnects by itself whenever it is switched on nearby.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect and forget") }
            }
        }
    }
}

@Composable
private fun BluetoothSection(
    bluetooth: BluetoothUi,
    onRequestPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onConnect: (BluetoothMidiScanner.Found) -> Unit,
) {
    when {
        !bluetooth.available -> Text("This device cannot use Bluetooth MIDI.")

        !bluetooth.permissionGranted -> {
            Text(
                if (bluetooth.permissionDenied) {
                    "Permission was refused. If the button below does nothing, allow \"Nearby devices\" " +
                        "for Lumi Keys in the system settings."
                } else {
                    "Lumi Keys needs permission to find and connect to the keyboard."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRequestPermission) { Text("Allow Bluetooth") }
        }

        !bluetooth.enabled -> {
            Text("Bluetooth is switched off.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onEnableBluetooth) { Text("Turn on Bluetooth") }
        }

        else -> {
            bluetooth.scanError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (bluetooth.scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    when {
                        !bluetooth.scanning -> "Not searching."
                        bluetooth.rememberedName != null ->
                            "Waiting for ${bluetooth.rememberedName}. Switch the keyboard on."
                        else -> "Searching. Switch the keyboard on."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            bluetooth.devices.forEach { found ->
                Card(onClick = { onConnect(found) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(found.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Signal ${signalLabel(found.rssi)}. Tap to connect.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/** The keyboard's own settings. Everything here is stored on the keyboard, not in the app. */
@Composable
private fun KeyboardSettingsSection(keyboard: KeyboardInfo, onSet: (item: Int, value: Int) -> Unit) {
    when (keyboard.status) {
        KeyboardInfo.Status.NOT_CONNECTED -> return
        KeyboardInfo.Status.READING -> {
            Text("Reading the keyboard's settings", style = MaterialTheme.typography.bodyMedium)
            return
        }
        KeyboardInfo.Status.NO_ANSWER -> {
            Text(
                "The keyboard did not answer a settings request, so its settings cannot be shown.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return
        }
        KeyboardInfo.Status.READY -> Unit
    }

    Text("Keyboard settings", style = MaterialTheme.typography.titleMedium)
    Text(
        listOfNotNull(
            keyboard.firmware?.let { "Firmware $it" },
            keyboard.batteryPercent?.let { "Battery $it%" + if (keyboard.charging) " (charging)" else "" },
        ).joinToString("   "),
        style = MaterialTheme.typography.bodySmall,
    )

    keyboard.setting(LumiProtocol.ITEM_MODE)?.let { mode ->
        Text("Light mode", style = MaterialTheme.typography.titleSmall)
        // Wraps onto further lines on narrow screens so the selected mode is always visible.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (slot in mode.min..mode.max) {
                val scheme = keyboard.setting(LumiProtocol.colourSchemeItem(slot))?.value
                val name = scheme?.let(LumiProtocol.COLOUR_SCHEME_NAMES::getOrNull) ?: "Mode"
                FilterChip(
                    selected = slot == mode.value,
                    onClick = { onSet(LumiProtocol.ITEM_MODE, slot) },
                    label = { Text("${slot + 1} $name", maxLines = 1, softWrap = false) },
                )
            }
        }
    }

    keyboard.setting(LumiProtocol.ITEM_OCTAVE)?.let { octave ->
        val base = LumiProtocol.baseNoteForOctave(octave.value)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Octave", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = { onSet(LumiProtocol.ITEM_OCTAVE, octave.value - 1) },
                enabled = octave.value > octave.min,
                modifier = Modifier.semantics { contentDescription = "Keyboard octave down" },
            ) { Text("\u2212") }
            Text(
                "${octave.value}  (${KeyboardLayout.noteName(base)} to " +
                    "${KeyboardLayout.noteName(base + KeyboardLayout.KEY_COUNT - 1)})",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = { onSet(LumiProtocol.ITEM_OCTAVE, octave.value + 1) },
                enabled = octave.value < octave.max,
                modifier = Modifier.semantics { contentDescription = "Keyboard octave up" },
            ) { Text("+") }
        }
    }

    keyboard.setting(LumiProtocol.ITEM_BRIGHTNESS)?.let {
        SettingSlider("Brightness", LumiProtocol.ITEM_BRIGHTNESS, it, onSet)
    }
    keyboard.setting(LumiProtocol.ITEM_STRIKE_SENSITIVITY)?.let {
        SettingSlider("Strike sensitivity", LumiProtocol.ITEM_STRIKE_SENSITIVITY, it, onSet)
    }

    Text(
        "These are stored on the keyboard itself and stay after it is switched off " +
            "(except the light mode, which it chooses again at power-on).",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** A slider that only writes to the keyboard when the finger lifts, not on every step. */
@Composable
private fun SettingSlider(
    label: String,
    item: Int,
    setting: KeyboardInfo.Setting,
    onSet: (item: Int, value: Int) -> Unit,
) {
    var position by remember(setting.value) { mutableFloatStateOf(setting.value.toFloat()) }
    Text("$label: ${position.roundToInt()}", style = MaterialTheme.typography.titleSmall)
    Slider(
        value = position,
        onValueChange = { position = it },
        onValueChangeFinished = { onSet(item, position.roundToInt()) },
        valueRange = setting.min.toFloat()..setting.max.toFloat(),
        modifier = Modifier.semantics { contentDescription = label },
    )
}

private fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "strong"
    rssi >= -80 -> "medium"
    else -> "weak"
}
