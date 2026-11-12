package io.github.mrlouix.sonorama.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.data.Limits
import io.github.mrlouix.sonorama.ui.MainViewModel
import io.github.mrlouix.sonorama.ui.Screen
import io.github.mrlouix.sonorama.ui.ScreenTopBar
import io.github.mrlouix.sonorama.ui.Symbols
import io.github.mrlouix.sonorama.ui.text

private val ROW_HEIGHT = 60.dp

/** Éditeur de page personnalisée : nom, symbole, cartes et leur ordre. */
@Composable
fun PageEditorScreen(viewModel: MainViewModel) {
    val draft by viewModel.pageDraft.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val page = draft ?: return
    Scaffold(
        topBar = {
            ScreenTopBar(
                title = stringResource(if (page.pageId == null) R.string.page_editor_new else R.string.page_editor_edit),
                onBack = { viewModel.back() },
                actions = {
                    TextButton(onClick = viewModel::savePage, enabled = page.name.isNotBlank()) {
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
                    value = page.name,
                    onValueChange = { text -> viewModel.updatePageDraft { it.copy(name = text.take(Limits.PAGE_NAME_MAX)) } },
                    label = { Text(stringResource(R.string.field_name)) },
                    supportingText = { Text("${page.name.length}/${Limits.PAGE_NAME_MAX}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                SymbolButton(
                    symbol = page.symbol,
                    onSymbol = { symbol -> viewModel.updatePageDraft { it.copy(symbol = symbol) } },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.open(Screen.CardPicker) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_choose_cards))
                }
                OutlinedButton(onClick = viewModel::newComposite, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_new_composite))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.page_editor_count, page.cardIds.size, Limits.PAGE_CARDS_MAX),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (page.cardIds.isEmpty()) {
                Text(stringResource(R.string.page_editor_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
            var dragged by remember { mutableStateOf<String?>(null) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            val move by rememberUpdatedState(viewModel::movePageCard)
            val ids by rememberUpdatedState(page.cardIds)
            Column {
                page.cardIds.forEachIndexed { index, cardId ->
                    key(cardId) {
                        val card = library.cards[cardId]
                        val name = card?.name?.text() ?: cardId
                        val isDragged = dragged == cardId
                        Row(
                            Modifier
                                .zIndex(if (isDragged) 1f else 0f)
                                .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                                .fillMaxWidth()
                                .height(ROW_HEIGHT)
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isDragged) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                )
                                .alpha(if (card?.available == false) 0.5f else 1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .pointerInput(cardId) {
                                        detectDragGestures(
                                            onDragStart = {
                                                dragged = cardId
                                                dragOffset = 0f
                                            },
                                            onDragEnd = { dragged = null },
                                            onDragCancel = { dragged = null },
                                        ) { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            // Franchir la moitié d'une ligne échange la carte avec sa voisine.
                                            val position = ids.indexOf(cardId)
                                            if (dragOffset > rowHeightPx / 2 && position < ids.lastIndex) {
                                                move(cardId, 1)
                                                dragOffset -= rowHeightPx
                                            } else if (dragOffset < -rowHeightPx / 2 && position > 0) {
                                                move(cardId, -1)
                                                dragOffset += rowHeightPx
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.DragHandle, contentDescription = null)
                            }
                            Icon(Symbols.icon(card?.symbol ?: Symbols.DEFAULT), contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { viewModel.movePageCard(cardId, -1) }, enabled = index > 0) {
                                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.action_move_up, name))
                            }
                            IconButton(onClick = { viewModel.movePageCard(cardId, 1) }, enabled = index < page.cardIds.lastIndex) {
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.action_move_down, name))
                            }
                            IconButton(onClick = { viewModel.togglePageCard(cardId) }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove_card, name))
                            }
                        }
                    }
                }
            }
        }
    }
}
