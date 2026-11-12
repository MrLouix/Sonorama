package io.github.mrlouix.sonorama.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrlouix.sonorama.BuildConfig
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.data.SettingsStore
import io.github.mrlouix.sonorama.data.ThemeMode
import io.github.mrlouix.sonorama.ui.MainViewModel
import io.github.mrlouix.sonorama.ui.Screen
import io.github.mrlouix.sonorama.ui.ScreenTopBar
import io.github.mrlouix.sonorama.ui.isFrench
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: MainViewModel, onLanguageChanged: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = { ScreenTopBar(stringResource(R.string.settings_title), onBack = { viewModel.back() }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionTitle(R.string.settings_fades)
            val fades = settings.fades
            FadeSlider(R.string.settings_fade_in, fades.fadeInMs) { viewModel.setFades(fades.copy(fadeInMs = it)) }
            FadeSlider(R.string.settings_fade_out, fades.fadeOutMs) { viewModel.setFades(fades.copy(fadeOutMs = it)) }
            FadeSlider(R.string.settings_crossfade, fades.crossfadeMs) { viewModel.setFades(fades.copy(crossfadeMs = it)) }

            SectionTitle(R.string.settings_theme)
            Column(Modifier.selectableGroup()) {
                Choice(R.string.settings_follow_system, settings.theme == ThemeMode.SYSTEM) { viewModel.setTheme(ThemeMode.SYSTEM) }
                Choice(R.string.settings_theme_light, settings.theme == ThemeMode.LIGHT) { viewModel.setTheme(ThemeMode.LIGHT) }
                Choice(R.string.settings_theme_dark, settings.theme == ThemeMode.DARK) { viewModel.setTheme(ThemeMode.DARK) }
            }

            SectionTitle(R.string.settings_language)
            Column(Modifier.selectableGroup()) {
                for ((code, label) in listOf("" to R.string.settings_follow_system, "fr" to R.string.language_french, "en" to R.string.language_english)) {
                    Choice(label, settings.language == code) {
                        if (settings.language != code) viewModel.setLanguage(code, onLanguageChanged)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { viewModel.open(Screen.Licenses) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.licenses_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
            HorizontalDivider()

            SectionTitle(R.string.settings_about)
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.about_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun FadeSlider(label: Int, valueMs: Int, onChange: (Int) -> Unit) {
    var local by remember(valueMs) { mutableFloatStateOf(valueMs / 1000f) }
    val name = stringResource(label)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.seconds, local), style = MaterialTheme.typography.labelLarge)
    }
    Slider(
        value = local,
        onValueChange = { local = (it * 10).roundToInt() / 10f },
        onValueChangeFinished = { onChange((local * 1000).roundToInt()) },
        valueRange = 0f..SettingsStore.MAX_FADE_MS / 1000f,
        steps = SettingsStore.MAX_FADE_MS / 100 - 1,
        modifier = Modifier.semantics { contentDescription = name },
    )
}

@Composable
private fun Choice(label: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Text(stringResource(label))
    }
}

/** Auteur, licence et source de chaque son installé. */
@Composable
fun LicensesScreen(viewModel: MainViewModel) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val french = isFrench()
    Scaffold(topBar = { ScreenTopBar(stringResource(R.string.licenses_title), onBack = { viewModel.back() }) }) { padding ->
        SelectionContainer(Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
                for (pack in library.packs.filter { it.installed }) {
                    item(key = "p:${pack.id}") {
                        Text(
                            pack.name.get(french),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    val sounds = library.sounds.values.filter { it.packId == pack.id && it.available }
                    items(sounds.size, key = { sounds[it].id }) { index ->
                        val sound = sounds[index]
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(sound.name.get(french), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(
                                    R.string.license_line,
                                    sound.author.ifBlank { stringResource(R.string.license_unknown_author) },
                                    sound.license,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (sound.source.isNotBlank()) {
                                Text(
                                    sound.source,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.licenses_software),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
