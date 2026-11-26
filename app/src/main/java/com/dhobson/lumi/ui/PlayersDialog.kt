package com.dhobson.lumi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dhobson.lumi.learn.Player
import com.dhobson.lumi.learn.PlayerRoster

/** The dot beside a player's name; grey for Everyone. */
@Composable
private fun playerColor(player: Player?): Color {
    check(LumiStyle.layerPalette.size >= PlayerRoster.COLOR_COUNT)
    return if (player == null) MaterialTheme.colorScheme.outline else LumiStyle.layerPalette[player.colorIndex]
}

/** Shows whose turn it is; tapping it opens the list of players. */
@Composable
fun PlayerButton(roster: PlayerRoster, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier.widthIn(max = 190.dp).semantics { contentDescription = "Player: ${roster.currentName}. Tap to change." },
    ) {
        // The dot's colour only decorates; the name says who it is.
        DotGlyph(Modifier.size(10.dp), color = playerColor(roster.current))
        Spacer(Modifier.width(8.dp))
        Text(roster.currentName, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Choose who is playing, and add, rename or remove players. Choosing or adding a
 * player closes the dialog, since that is nearly always all that was wanted.
 */
@Composable
fun PlayersDialog(
    roster: PlayerRoster,
    onSelect: (Int) -> Unit,
    /** Each returns null when done, or the reason it could not be done. */
    onAdd: (String) -> String?,
    onRename: (Int, String) -> String?,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var renameText by remember { mutableStateOf(TextFieldValue("")) }
    var confirmRemove by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Who is playing?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Each player keeps their own best scores in Learn. " +
                        "Choose Everyone when it does not matter who is playing.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                ChoiceRow(
                    name = PlayerRoster.EVERYONE_NAME,
                    color = playerColor(null),
                    selected = roster.current == null,
                    onClick = {
                        onSelect(PlayerRoster.EVERYONE_ID)
                        onDismiss()
                    },
                )
                for (player in roster.players) {
                    when {
                        renaming == player.id -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // Ready to type in straight away, with the cursor after the old name.
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            OutlinedTextField(
                                value = renameText,
                                onValueChange = {
                                    if (it.text.length <= PlayerRoster.MAX_NAME_LENGTH) renameText = it
                                },
                                label = { Text("New name for ${player.name}") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).focusRequester(focus),
                            )
                            TextButton(
                                onClick = {
                                    problem = onRename(player.id, renameText.text)
                                    if (problem == null) renaming = null
                                },
                                enabled = renameText.text.isNotBlank(),
                                modifier = Modifier.semantics { contentDescription = "Save new name" },
                            ) { Text("OK") }
                            TextButton(onClick = { renaming = null; problem = null }) { Text("Cancel") }
                        }

                        confirmRemove == player.id -> Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    player.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                                )
                                // Asks twice: the player's best scores are deleted with them.
                                TextButton(
                                    onClick = {
                                        confirmRemove = null
                                        onRemove(player.id)
                                    },
                                    modifier = Modifier.semantics { contentDescription = "Really remove ${player.name}" },
                                ) { Text("Really remove?", color = MaterialTheme.colorScheme.error) }
                                TextButton(onClick = { confirmRemove = null }) { Text("Keep") }
                            }
                            Text(
                                "${player.name}'s best scores will be deleted too.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }

                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            ChoiceRow(
                                name = player.name,
                                color = playerColor(player),
                                selected = roster.currentId == player.id,
                                onClick = {
                                    onSelect(player.id)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    renaming = player.id
                                    renameText = TextFieldValue(player.name, TextRange(player.name.length))
                                    confirmRemove = null
                                    problem = null
                                },
                                modifier = Modifier.semantics { contentDescription = "Rename ${player.name}" },
                            ) { Text("Rename") }
                            TextButton(
                                onClick = {
                                    confirmRemove = player.id
                                    renaming = null
                                    problem = null
                                },
                                modifier = Modifier.semantics { contentDescription = "Remove ${player.name}" },
                            ) { Text("Remove") }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(PlayerRoster.MAX_NAME_LENGTH) },
                        label = { Text("New player") },
                        singleLine = true,
                        enabled = !roster.isFull,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            problem = onAdd(newName)
                            if (problem == null) onDismiss()
                        },
                        enabled = newName.isNotBlank() && !roster.isFull,
                        modifier = Modifier.semantics { contentDescription = "Add player" },
                    ) { Text("Add", maxLines = 1, softWrap = false) }
                }
                if (roster.isFull) {
                    Text(
                        "That is as many players as there is room for. Remove one to add another.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                problem?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
    )
}

/** One player to choose: a radio button, their dot and their name. */
@Composable
private fun ChoiceRow(name: String, color: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Player $name" },
    ) {
        RadioButton(selected = selected, onClick = null)
        DotGlyph(Modifier.padding(start = 10.dp).size(12.dp), color = color)
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
