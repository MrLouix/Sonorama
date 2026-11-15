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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Fingerprint
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.data.Pack
import io.github.mrlouix.sonorama.ui.AppSnackbarHost
import io.github.mrlouix.sonorama.ui.MainViewModel
import io.github.mrlouix.sonorama.ui.ScreenTopBar
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
    var details by remember { mutableStateOf<Pack?>(null) }

    Scaffold(
        snackbarHost = { AppSnackbarHost() },
        topBar = { ScreenTopBar(stringResource(R.string.packs_title), onBack = { viewModel.back() }) }) { padding ->
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
                        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
                        if (pack.installed) {
                            Text(
                                trustLabel(pack.trust),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!pack.builtIn) {
                        IconButton(onClick = { details = pack }) {
                            Icon(Icons.Rounded.Fingerprint, contentDescription = stringResource(R.string.action_pack_fingerprint, name))
                        }
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

    details?.let { pack ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(pack.name.text()) },
            text = {
                Column {
                    Text(trustLabel(pack.trust))
                    Spacer(Modifier.height(12.dp))
                    Fingerprints(pack.archiveSha256, pack.signerKey)
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text(stringResource(R.string.action_close)) } },
        )
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
}
