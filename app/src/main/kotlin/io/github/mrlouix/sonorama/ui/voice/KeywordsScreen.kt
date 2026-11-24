package io.github.mrlouix.sonorama.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.data.Card
import io.github.mrlouix.sonorama.data.Keywords
import io.github.mrlouix.sonorama.ui.AppSnackbarHost
import io.github.mrlouix.sonorama.ui.MainViewModel
import io.github.mrlouix.sonorama.ui.ScreenTopBar
import io.github.mrlouix.sonorama.ui.Symbols
import io.github.mrlouix.sonorama.ui.text
import kotlinx.coroutines.delay

/** Mots-clés du mode vocal pour chaque carte d'une page, dans la langue de l'histoire. */
@Composable
fun KeywordsScreen(viewModel: MainViewModel) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val pageId by viewModel.keywordsPage.collectAsStateWithLifecycle()
    val voice by viewModel.voiceState.collectAsStateWithLifecycle()
    val page = library.page(pageId ?: "")
    val language = voice.language
    val cards = page?.cardIds.orEmpty().mapNotNull { library.cards[it] }
    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = {
            ScreenTopBar(
                stringResource(R.string.keywords_title, page?.name?.text().orEmpty()),
                onBack = { viewModel.back() },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(
                        R.string.keywords_intro,
                        stringResource(if (language == "fr") R.string.language_french else R.string.language_english),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(cards, key = { it.id }) { card ->
                CardKeywords(card, language, viewModel)
                HorizontalDivider(Modifier.padding(top = 12.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardKeywords(card: Card, language: String, viewModel: MainViewModel) {
    val saved = card.keywords[language].orEmpty()
    var text by remember(card.id, language, saved) { mutableStateOf(saved.joinToString(", ")) }
    val words = remember(text) { Keywords.clean(text.split(',', ';', '\n')) }
    var unknown by remember(card.id) { mutableStateOf(emptySet<String>()) }
    // Vérifie les mots contre le vocabulaire du modèle, après une courte pause dans la saisie.
    LaunchedEffect(words, language) {
        delay(400)
        val single = words.flatMap { it.split(' ') }.toSet()
        val missing = viewModel.unknownWords(language, single)
        unknown = words.filter { phrase -> phrase.split(' ').any { it in missing } }.toSet()
    }
    val changed = words != saved
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Symbols.icon(card.symbol), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(card.name.text(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (card.customKeywords && !card.composite) {
                TextButton(onClick = { viewModel.resetCardKeywords(card.id) }) {
                    Text(stringResource(R.string.action_reset_keywords))
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.keywords_field)) },
            supportingText = { Text(stringResource(R.string.keywords_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        if (words.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (word in words) {
                    val isUnknown = word in unknown
                    val unknownLabel = stringResource(R.string.keyword_unknown)
                    AssistChip(
                        onClick = {},
                        label = { Text(word) },
                        leadingIcon = if (isUnknown) {
                            { Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .alpha(if (isUnknown) 0.5f else 1f)
                            .semantics { if (isUnknown) contentDescription = "$word, $unknownLabel" },
                    )
                }
            }
        }
        if (unknown.isNotEmpty()) {
            Text(
                stringResource(R.string.keywords_unknown_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (changed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { text = saved.joinToString(", ") }) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = { viewModel.setCardKeywords(card.id, language, words) }) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
