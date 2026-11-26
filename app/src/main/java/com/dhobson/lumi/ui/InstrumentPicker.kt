package com.dhobson.lumi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dhobson.lumi.music.Instrument
import com.dhobson.lumi.music.Instruments

private const val FAVOURITES_TAB = "Favourites"

@Composable
fun InstrumentPicker(
    selectedId: Int,
    favourites: Set<Int>,
    onSelect: (Int) -> Unit,
    onToggleFavourite: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedFamily = Instruments.families.first { family -> family.instruments.any { it.id == selectedId } }
    var tab by rememberSaveable { mutableStateOf(selectedFamily.name) }
    val tabs = listOf(FAVOURITES_TAB) + Instruments.families.map { it.name }
    val instruments: List<Instrument> = if (tab == FAVOURITES_TAB) {
        favourites.sorted().mapNotNull(Instruments::byId)
    } else {
        Instruments.families.first { it.name == tab }.instruments
    }

    Column(modifier) {
        BoxWithConstraints {
            if (maxWidth >= 600.dp) {
                // Room to show every family at once, so the chosen one is always in view.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    tabs.forEach { name ->
                        FilterChip(selected = name == tab, onClick = { tab = name }, label = { Text(name) })
                    }
                }
            } else {
                // A narrow screen: one row that scrolls sideways, starting at the chosen family.
                LazyRow(
                    state = rememberLazyListState(initialFirstVisibleItemIndex = tabs.indexOf(tab).coerceAtLeast(0)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(tabs) { name ->
                        FilterChip(selected = name == tab, onClick = { tab = name }, label = { Text(name) })
                    }
                }
            }
        }

        if (instruments.isEmpty()) {
            Text(
                "No favourites yet. Tap the star on an instrument to add it here.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 220.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(instruments, key = { it.id }) { instrument ->
                InstrumentCard(
                    instrument = instrument,
                    selected = instrument.id == selectedId,
                    favourite = instrument.id in favourites,
                    onSelect = { onSelect(instrument.id) },
                    onToggleFavourite = { onToggleFavourite(instrument.id) },
                )
            }
        }
    }
}

@Composable
private fun InstrumentCard(
    instrument: Instrument,
    selected: Boolean,
    favourite: Boolean,
    onSelect: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val colors = if (selected) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors()
    }
    Card(onClick = onSelect, colors = colors, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        ) {
            Text(
                instrument.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onToggleFavourite,
                modifier = Modifier.semantics {
                    contentDescription = if (favourite) "Remove from favourites" else "Add to favourites"
                },
            ) {
                Text(
                    if (favourite) "\u2605" else "\u2606",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (favourite) LumiStyle.gold else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
