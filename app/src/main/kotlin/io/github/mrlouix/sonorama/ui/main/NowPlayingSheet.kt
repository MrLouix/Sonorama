package io.github.mrlouix.sonorama.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.audio.MixState
import io.github.mrlouix.sonorama.data.Library
import io.github.mrlouix.sonorama.ui.Symbols
import io.github.mrlouix.sonorama.ui.text
import kotlin.math.roundToInt

/** Panneau « En cours » : toutes les cartes actives, leur volume, l'arrêt, l'enregistrement du mix. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    library: Library,
    mix: MixState,
    onDismiss: () -> Unit,
    onVolume: (cardId: String, volume: Int, commit: Boolean) -> Unit,
    onStop: (String) -> Unit,
    onStopAll: () -> Unit,
    onSaveMix: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.now_playing_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            val cards = mix.active.mapNotNull { library.cards[it] }
            if (cards.isEmpty()) {
                Text(
                    stringResource(R.string.now_playing_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(cards, key = { it.id }) { card ->
                    val change = mix.plan[card.id]
                    val volume = change?.volume ?: mix.volumes[card.id] ?: card.volume
                    var local by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
                    val name = card.name.text()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Symbols.icon(card.symbol), contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (change?.toggle == true) {
                                    Text(
                                        stringResource(R.string.card_state_pending_stop),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                            Text(stringResource(R.string.percent, local.roundToInt()), style = MaterialTheme.typography.labelLarge)
                            IconButton(onClick = { onStop(card.id) }) {
                                if (change?.toggle == true) {
                                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.action_keep_playing, name))
                                } else {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_stop_card, name))
                                }
                            }
                        }
                        val sliderLabel = stringResource(R.string.volume_of, name)
                        Slider(
                            value = local,
                            onValueChange = {
                                local = it
                                onVolume(card.id, it.roundToInt(), false)
                            },
                            onValueChangeFinished = { onVolume(card.id, local.roundToInt(), true) },
                            valueRange = 0f..100f,
                            modifier = Modifier.semantics { contentDescription = sliderLabel },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onStopAll,
                    enabled = cards.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_stop_all)) }
                Button(
                    onClick = onSaveMix,
                    enabled = cards.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_save_mix)) }
            }
        }
    }
}
