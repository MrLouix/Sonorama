package io.github.mrlouix.sonorama.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.data.Library
import io.github.mrlouix.sonorama.data.Limits
import io.github.mrlouix.sonorama.ui.CompositeDraft
import io.github.mrlouix.sonorama.ui.MainViewModel
import io.github.mrlouix.sonorama.ui.ScreenTopBar
import io.github.mrlouix.sonorama.ui.Symbols
import io.github.mrlouix.sonorama.ui.isFrench
import kotlin.math.roundToInt

/** Éditeur de carte composée ; sert aussi à nommer une scène enregistrée depuis le mix en cours. */
@Composable
fun CompositeEditorScreen(viewModel: MainViewModel) {
    val draftState by viewModel.compositeDraft.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val draft = draftState ?: return
    val french = isFrench()
    var pickSounds by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = stringResource(
                    when {
                        draft.fromMix -> R.string.composite_editor_scene
                        draft.cardId == null -> R.string.composite_editor_new
                        else -> R.string.composite_editor_edit
                    },
                ),
                onBack = { viewModel.back() },
                actions = {
                    TextButton(onClick = viewModel::saveComposite, enabled = draft.isValid) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { text -> viewModel.updateCompositeDraft { it.copy(name = text.take(Limits.CARD_NAME_MAX)) } },
                    label = { Text(stringResource(R.string.field_name)) },
                    supportingText = { Text("${draft.name.length}/${Limits.CARD_NAME_MAX}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                SymbolButton(
                    symbol = draft.symbol,
                    onSymbol = { symbol -> viewModel.updateCompositeDraft { it.copy(symbol = symbol) } },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { pickSounds = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_choose_sounds))
                }
                FilledTonalButton(
                    onClick = { viewModel.updateCompositeDraft { it.copy(listening = !it.listening) } },
                    enabled = draft.sounds.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(if (draft.listening) Icons.Rounded.Stop else Icons.Rounded.Hearing, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (draft.listening) R.string.action_stop_listening else R.string.action_listen))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(
                    R.string.composite_editor_count,
                    draft.sounds.size,
                    Limits.COMPOSITE_SOUNDS_MIN,
                    Limits.COMPOSITE_SOUNDS_MAX,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (part in draft.sounds) {
                val sound = library.sounds[part.soundId]
                val name = sound?.name?.get(french) ?: part.soundId
                Column(Modifier.padding(top = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Symbols.icon(sound?.symbol ?: Symbols.DEFAULT), contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.percent, part.relativeVolume), style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { viewModel.toggleCompositeSound(part.soundId) }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove_card, name))
                        }
                    }
                    val sliderLabel = stringResource(R.string.volume_of, name)
                    Slider(
                        value = part.relativeVolume.toFloat(),
                        onValueChange = { value ->
                            viewModel.updateCompositeDraft { current ->
                                current.copy(
                                    sounds = current.sounds.map {
                                        if (it.soundId == part.soundId) it.copy(relativeVolume = value.roundToInt()) else it
                                    },
                                )
                            }
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.semantics { contentDescription = sliderLabel },
                    )
                }
            }
            if (draft.cardId != null) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_delete_card), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (pickSounds) {
        SoundPickerDialog(library, draft, french, onToggle = viewModel::toggleCompositeSound, onClose = { pickSounds = false })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_card_title, draft.name)) },
            text = { Text(stringResource(R.string.delete_card_text)) },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    draft.cardId?.let(viewModel::deleteComposite)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SoundPickerDialog(
    library: Library,
    draft: CompositeDraft,
    french: Boolean,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
) {
    val chosen = draft.sounds.map { it.soundId }.toSet()
    val sounds = library.sounds.values
        .filter { it.available }
        .sortedWith(compareBy({ it.pageId }, { it.name.get(french) }))
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.sound_picker_title, chosen.size, Limits.COMPOSITE_SOUNDS_MAX)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(sounds, key = { it.id }) { sound ->
                    val selected = sound.id in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .toggleable(value = selected, role = Role.Checkbox) { onToggle(sound.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        Icon(Symbols.icon(sound.symbol), contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(sound.name.get(french), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_done)) } },
    )
}
