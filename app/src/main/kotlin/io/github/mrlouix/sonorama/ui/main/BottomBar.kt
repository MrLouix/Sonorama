package io.github.mrlouix.sonorama.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.audio.MixMode
import io.github.mrlouix.sonorama.audio.MixState

/** Barre de commande fixe : mode, play/pause, compteur ; Appliquer et Annuler quand un plan attend. */
@Composable
fun BottomBar(
    mix: MixState,
    onTogglePlayback: () -> Unit,
    onMode: (MixMode) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (mix.plan.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = onApply, modifier = Modifier.weight(2f).heightIn(min = 48.dp)) {
                        Text(
                            pluralStringResource(R.plurals.apply_changes, mix.plan.size, mix.plan.size),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    SingleChoiceSegmentedButtonRow {
                        MixMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = mix.mode == mode,
                                onClick = { if (mix.mode != mode) onMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, MixMode.entries.size),
                                icon = {},
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(
                                    stringResource(if (mode == MixMode.DIRECT) R.string.mode_direct else R.string.mode_plan),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                val playLabel = stringResource(if (mix.playing) R.string.action_pause else R.string.action_play)
                FilledIconButton(
                    onClick = onTogglePlayback,
                    modifier = Modifier.padding(horizontal = 12.dp).size(width = 96.dp, height = 64.dp),
                ) {
                    Icon(
                        if (mix.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = playLabel,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    val count = mix.active.size
                    val label = pluralStringResource(R.plurals.active_cards, count, count)
                    FilledTonalButton(
                        onClick = onOpenNowPlaying,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
                    ) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(count.toString(), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
