package io.github.mrlouix.sonorama.ui.packs

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.data.Pack
import io.github.mrlouix.sonorama.data.pack.ImportResult
import io.github.mrlouix.sonorama.data.pack.ImportStatus
import io.github.mrlouix.sonorama.data.pack.PackError
import io.github.mrlouix.sonorama.ui.MainViewModel
import io.github.mrlouix.sonorama.ui.ScreenTopBar
import io.github.mrlouix.sonorama.ui.isFrench
import io.github.mrlouix.sonorama.ui.text

/** Sélecteur de fichiers du système, ouvert sur Téléchargements et filtré sur les ZIP. */
private class PickPacks : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            "content://com.android.externalstorage.documents/document/primary%3ADownload".toUri(),
        )
}

private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed")

@Composable
fun PacksScreen(viewModel: MainViewModel) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val import by viewModel.importState.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(PickPacks()) { uris -> viewModel.importPacks(uris) }
    var toDelete by remember { mutableStateOf<Pack?>(null) }

    Scaffold(topBar = { ScreenTopBar(stringResource(R.string.packs_title), onBack = { viewModel.back() }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(stringResource(R.string.packs_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { launcher.launch(ZIP_TYPES) },
                    enabled = !import.running,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text(stringResource(R.string.action_import_pack)) }
                if (import.running) {
                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.import_progress, import.fileIndex + 1, import.fileCount, import.currentFile),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { import.progress }, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = viewModel::cancelImport, modifier = Modifier.align(Alignment.End)) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.packs_installed), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
            }
            items(library.packs, key = { it.id }) { pack ->
                val name = pack.name.text()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val detail = when {
                            !pack.installed -> stringResource(R.string.pack_uninstalled)
                            pack.builtIn -> stringResource(
                                R.string.pack_detail_builtin,
                                pluralStringResource(R.plurals.sounds, pack.soundCount, pack.soundCount),
                            )
                            else -> stringResource(
                                R.string.pack_detail,
                                pluralStringResource(R.plurals.sounds, pack.soundCount, pack.soundCount),
                                pack.version,
                            )
                        }
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (pack.installed && !pack.builtIn) {
                        IconButton(onClick = { toDelete = pack }) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete_pack, name))
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_pack_title, pack.name.text())) },
            text = { Text(stringResource(R.string.delete_pack_text)) },
            confirmButton = {
                Button(onClick = {
                    toDelete = null
                    viewModel.uninstallPack(pack.id)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    import.question?.let { question ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.replace_pack_title, question.packName.get(isFrench()))) },
            text = { Text(stringResource(R.string.replace_pack_text, question.installedVersion, question.newVersion)) },
            confirmButton = { Button(onClick = { viewModel.answerImport(true) }) { Text(stringResource(R.string.action_replace)) } },
            dismissButton = { TextButton(onClick = { viewModel.answerImport(false) }) { Text(stringResource(R.string.action_ignore)) } },
        )
    }

    if (!import.running && import.results.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissImportResults,
            title = { Text(stringResource(R.string.import_report_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (result in import.results) {
                        Text(
                            result.packName?.get(isFrench()) ?: result.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(describe(result), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissImportResults) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun describe(result: ImportResult): String = when (result.status) {
    ImportStatus.INSTALLED -> pluralStringResource(R.plurals.import_installed, result.soundsAdded, result.soundsAdded)
    ImportStatus.UPDATED -> stringResource(
        R.string.import_updated,
        pluralStringResource(R.plurals.sounds, result.soundCount, result.soundCount),
        pluralStringResource(R.plurals.import_new_sounds, result.soundsAdded, result.soundsAdded),
    )
    ImportStatus.REPLACED -> stringResource(
        R.string.import_replaced,
        pluralStringResource(R.plurals.sounds, result.soundCount, result.soundCount),
    )
    ImportStatus.IGNORED -> stringResource(R.string.import_ignored)
    ImportStatus.CANCELLED -> stringResource(R.string.import_cancelled)
    ImportStatus.REFUSED -> {
        val reason = stringResource(
            when (result.error) {
                PackError.NOT_A_ZIP -> R.string.pack_error_not_a_zip
                PackError.MANIFEST_MISSING -> R.string.pack_error_manifest_missing
                PackError.MANIFEST_UNREADABLE -> R.string.pack_error_manifest_unreadable
                PackError.UNKNOWN_FORMAT -> R.string.pack_error_unknown_format
                PackError.INVALID_ID, PackError.RESERVED_ID -> R.string.pack_error_invalid_id
                PackError.NO_SOUNDS -> R.string.pack_error_no_sounds
                PackError.TOO_MANY_SOUNDS -> R.string.pack_error_too_many_sounds
                PackError.DUPLICATE_SOUND -> R.string.pack_error_duplicate_sound
                PackError.INVALID_NAME -> R.string.pack_error_invalid_name
                PackError.UNSAFE_PATH -> R.string.pack_error_unsafe_path
                PackError.UNSUPPORTED_AUDIO -> R.string.pack_error_unsupported_audio
                PackError.UNKNOWN_PAGE -> R.string.pack_error_unknown_page
                PackError.FORBIDDEN_LICENSE -> R.string.pack_error_forbidden_license
                PackError.FILE_MISSING -> R.string.pack_error_file_missing
                PackError.TOO_LARGE -> R.string.pack_error_too_large
                PackError.IO, null -> R.string.pack_error_io
            },
        )
        val detail = if (result.detail.isBlank()) "" else " (${result.detail})"
        stringResource(R.string.import_refused, reason + detail)
    }
}
