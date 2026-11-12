package io.github.mrlouix.sonorama.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrlouix.sonorama.ui.editor.CardPickerScreen
import io.github.mrlouix.sonorama.ui.editor.CompositeEditorScreen
import io.github.mrlouix.sonorama.ui.editor.PageEditorScreen
import io.github.mrlouix.sonorama.ui.main.MainScreen
import io.github.mrlouix.sonorama.ui.packs.PacksScreen
import io.github.mrlouix.sonorama.ui.settings.LicensesScreen
import io.github.mrlouix.sonorama.ui.settings.SettingsScreen
import io.github.mrlouix.sonorama.ui.theme.SonoramaTheme
import kotlinx.coroutines.launch

@Composable
fun SonoramaRoot(viewModel: MainViewModel, onLanguageChanged: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val backStack by viewModel.backStack.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(viewModel, resources) {
        viewModel.messages.collect { message ->
            // Un message chasse le précédent : seul le dernier retour compte pendant une lecture.
            snackbar.currentSnackbarData?.dismiss()
            launch {
                val result = snackbar.showSnackbar(
                    message = resources.getString(message.text, *message.args.toTypedArray()),
                    actionLabel = message.actionLabel?.let { resources.getString(it) },
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed && message.action == MessageAction.OPEN_PACKS) {
                    viewModel.open(Screen.Packs)
                }
            }
        }
    }

    SonoramaTheme(settings.theme) {
        BackHandler(enabled = backStack.size > 1) { viewModel.back() }
        when (backStack.last()) {
            Screen.Main -> MainScreen(viewModel, snackbar)
            Screen.Packs -> PacksScreen(viewModel)
            Screen.Settings -> SettingsScreen(viewModel, onLanguageChanged)
            Screen.Licenses -> LicensesScreen(viewModel)
            Screen.PageEditor -> PageEditorScreen(viewModel)
            Screen.CardPicker -> CardPickerScreen(viewModel)
            Screen.CompositeEditor -> CompositeEditorScreen(viewModel)
        }
    }
}
