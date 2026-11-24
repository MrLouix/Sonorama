package io.github.mrlouix.sonorama.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.audio.MixMode
import io.github.mrlouix.sonorama.audio.MixState
import io.github.mrlouix.sonorama.voice.VoiceState

// Largeurs que demandent les éléments à la taille de police normale ; elles grandissent avec elle.
private val MODE_WIDTH = 146.dp
private val COUNTER_WIDTH = 80.dp
private val MIC_WIDTH = 56.dp
private val PLAY_WIDE = 96.dp
private val PLAY_NARROW = 72.dp
private val GAP = 12.dp

/**
 * Barre de commande fixe : mode, play/pause, compteur ; Appliquer et Annuler quand un plan attend.
 * Sur un écran étroit ou avec une grande police, la barre passe sur deux lignes au lieu de tronquer ses libellés.
 */
@Composable
fun BottomBar(
    mix: MixState,
    voice: VoiceState,
    onVoice: () -> Unit,
    onTogglePlayback: () -> Unit,
    onMode: (MixMode) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        BoxWithConstraints(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
            val fontScale = LocalDensity.current.fontScale
            val sides = (MODE_WIDTH + COUNTER_WIDTH) * fontScale + MIC_WIDTH
            val playWidth = when {
                maxWidth >= sides + PLAY_WIDE + GAP * 2 -> PLAY_WIDE
                maxWidth >= sides + PLAY_NARROW + GAP * 2 -> PLAY_NARROW
                else -> null
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (voice.enabled) VoiceStatus(voice)
                if (mix.plan.isNotEmpty()) {
                    PlanActions(mix.plan.size, stacked = playWidth == null, onApply = onApply, onCancel = onCancel)
                }
                if (playWidth != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { ModeSwitch(mix.mode, onMode) }
                        PlayButton(mix.playing, onTogglePlayback, Modifier.padding(horizontal = GAP).size(playWidth, 64.dp))
                        MicButton(voice, onVoice)
                        Spacer(Modifier.width(8.dp))
                        Counter(mix.active.size, onOpenNowPlaying)
                    }
                } else {
                    ModeSwitch(mix.mode, onMode, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PlayButton(mix.playing, onTogglePlayback, Modifier.weight(1f).height(64.dp))
                        Spacer(Modifier.width(GAP))
                        MicButton(voice, onVoice)
                        Spacer(Modifier.width(8.dp))
                        Counter(mix.active.size, onOpenNowPlaying)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanActions(count: Int, stacked: Boolean, onApply: () -> Unit, onCancel: () -> Unit) {
    val apply = pluralStringResource(R.plurals.apply_changes, count, count)
    val cancel = stringResource(R.string.action_cancel)
    if (stacked) {
        // Libellés entiers, sur plusieurs lignes au besoin : le nombre de changements doit rester lisible.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(apply, textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(cancel, textAlign = TextAlign.Center)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(cancel, textAlign = TextAlign.Center)
            }
            Button(onClick = onApply, modifier = Modifier.weight(2f).heightIn(min = 48.dp)) {
                Text(apply, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ModeSwitch(mode: MixMode, onMode: (MixMode) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        MixMode.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = mode == entry,
                onClick = { if (mode != entry) onMode(entry) },
                shape = SegmentedButtonDefaults.itemShape(index, MixMode.entries.size),
                icon = {},
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(if (entry == MixMode.DIRECT) R.string.mode_direct else R.string.mode_plan),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FilledIconButton(onClick = onClick, modifier = modifier) {
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
            modifier = Modifier.size(36.dp),
        )
    }
}

/** Allume et éteint le mode vocal ; plein quand le micro écoute. */
@Composable
private fun MicButton(voice: VoiceState, onClick: () -> Unit) {
    val label = stringResource(R.string.action_voice)
    val state = stringResource(
        when {
            !voice.enabled -> R.string.voice_state_off
            voice.paused -> R.string.voice_state_paused
            else -> R.string.voice_state_on
        },
    )
    val modifier = Modifier.size(48.dp).semantics { stateDescription = state }
    when {
        !voice.enabled -> IconButton(onClick = onClick, modifier = modifier) {
            Icon(Icons.Rounded.MicOff, contentDescription = label)
        }
        voice.paused -> FilledTonalIconButton(onClick = onClick, modifier = modifier) {
            Icon(Icons.Rounded.MicOff, contentDescription = label)
        }
        else -> FilledIconButton(onClick = onClick, modifier = modifier) {
            Icon(Icons.Rounded.Mic, contentDescription = label)
        }
    }
}

/** Ce que fait le micro, et le dernier mot-clé entendu. */
@Composable
private fun VoiceStatus(voice: VoiceState) {
    val text = when {
        voice.paused -> stringResource(R.string.voice_status_paused)
        !voice.listening -> stringResource(R.string.voice_status_starting)
        voice.lastKeyword != null -> stringResource(R.string.voice_last_keyword, voice.lastKeyword)
        else -> pluralStringResource(R.plurals.voice_keyword_count, voice.keywordCount, voice.keywordCount)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Counter(count: Int, onClick: () -> Unit) {
    val label = pluralStringResource(R.plurals.active_cards, count, count)
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
    ) {
        Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
    }
}
