package io.github.mrlouix.sonorama.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.data.Card
import io.github.mrlouix.sonorama.data.Limits
import io.github.mrlouix.sonorama.ui.MainViewModel
import io.github.mrlouix.sonorama.ui.ScreenTopBar
import io.github.mrlouix.sonorama.ui.Symbols
import io.github.mrlouix.sonorama.ui.isFrench

/** Sélecteur de cartes : toutes les cartes installées, groupées par thème, avec recherche et pré-écoute. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardPickerScreen(viewModel: MainViewModel) {
    val draft by viewModel.pageDraft.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val previewing by viewModel.previewCard.collectAsStateWithLifecycle()
    val page = draft ?: return
    val french = isFrench()
    var query by rememberSaveable { mutableStateOf("") }

    val myCards = stringResource(R.string.group_my_cards)
    val matches = { card: Card -> query.isBlank() || card.name.get(french).contains(query.trim(), ignoreCase = true) }
    val available = library.cards.values.filter { it.available && matches(it) }
    val groups = buildList {
        val composites = available.filter { it.composite }
        if (composites.isNotEmpty()) add(myCards to composites)
        // Les thèmes suivent l'ordre des pages intégrées.
        for (theme in library.pages.filter { it.builtIn }) {
            val cards = available.filter { !it.composite && it.themePageId == theme.id }
            if (cards.isNotEmpty()) add(theme.name.get(french) to cards)
        }
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = stringResource(R.string.picker_title, page.cardIds.size, Limits.PAGE_CARDS_MAX),
                onBack = { viewModel.back() },
                actions = { TextButton(onClick = { viewModel.back() }) { Text(stringResource(R.string.action_done)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                label = { Text(stringResource(R.string.picker_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    OutlinedButton(
                        onClick = viewModel::newComposite,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.action_new_composite)) }
                }
                if (groups.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.picker_no_result),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                for ((title, cards) in groups) {
                    stickyHeader(key = "h:$title") {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(cards, key = { it.id }) { card ->
                        val name = card.name.get(french)
                        val selected = card.id in page.cardIds
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .toggleable(value = selected, role = Role.Checkbox) { viewModel.togglePageCard(card.id) }
                                .padding(start = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                            Icon(Symbols.icon(card.symbol), contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (card.composite) {
                                IconButton(onClick = { viewModel.editComposite(card.id) }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.action_edit_card, name))
                                }
                            }
                            IconButton(onClick = { viewModel.togglePreview(card) }) {
                                if (previewing == card.id) {
                                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.action_stop_preview, name))
                                } else {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.action_preview, name))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
