package io.github.mrlouix.sonorama.ui.packs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.data.pack.ImportResult
import io.github.mrlouix.sonorama.data.pack.ImportStatus
import io.github.mrlouix.sonorama.data.pack.KeyWarning
import io.github.mrlouix.sonorama.data.pack.PackError
import io.github.mrlouix.sonorama.data.pack.PackTrust
import io.github.mrlouix.sonorama.data.pack.ReplaceQuestion
import io.github.mrlouix.sonorama.ui.MainViewModel
import io.github.mrlouix.sonorama.ui.isFrench

/**
 * Dialogues de l'import, affichés quel que soit l'écran : la confirmation de remplacement
 * (sans laquelle l'import attendrait indéfiniment) et le bilan.
 */
@Composable
fun ImportDialogs(viewModel: MainViewModel) {
    val import by viewModel.importState.collectAsStateWithLifecycle()
    val question = import.question
    if (question != null) {
        ReplaceDialog(question, onAnswer = viewModel::answerImport)
    } else if (!import.running && import.results.isNotEmpty()) {
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
private fun ReplaceDialog(question: ReplaceQuestion, onAnswer: (Boolean) -> Unit) {
    var showFingerprints by remember(question) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.replace_pack_title, question.packName.get(isFrench()))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val installed = question.installedVersion
                Text(
                    if (installed != null) {
                        stringResource(R.string.replace_pack_text, installed, question.newVersion)
                    } else {
                        stringResource(R.string.reinstall_pack_text, question.newVersion)
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(trustLabel(question.trust), style = MaterialTheme.typography.bodyMedium)
                question.warning?.let { warning ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            when (warning) {
                                KeyWarning.KEY_CHANGED -> R.string.warning_key_changed
                                KeyWarning.SIGNATURE_DROPPED -> R.string.warning_signature_dropped
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (showFingerprints) {
                    Spacer(Modifier.height(12.dp))
                    Fingerprints(question.archiveSha256, question.signerKey)
                } else {
                    TextButton(onClick = { showFingerprints = true }) { Text(stringResource(R.string.action_show_fingerprint)) }
                }
            }
        },
        confirmButton = { Button(onClick = { onAnswer(true) }) { Text(stringResource(R.string.action_replace)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.action_ignore)) } },
    )
}

/** Empreintes à comparer avec celles publiées par l'auteur du pack ; le texte se sélectionne. */
@Composable
fun Fingerprints(archiveSha256: String?, signerKey: String?) {
    SelectionContainer {
        Column {
            if (archiveSha256 != null) {
                Text(stringResource(R.string.fingerprint_archive), style = MaterialTheme.typography.labelLarge)
                Text(grouped(archiveSha256), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            if (signerKey != null) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.fingerprint_key), style = MaterialTheme.typography.labelLarge)
                Text(grouped(signerKey), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            if (archiveSha256 == null && signerKey == null) {
                Text(stringResource(R.string.fingerprint_none), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Empreinte hexadécimale découpée par groupes de quatre, plus facile à comparer à l'œil. */
private fun grouped(hex: String): String = hex.chunked(4).joinToString(" ")

@Composable
fun trustLabel(trust: PackTrust): String = stringResource(
    when (trust) {
        PackTrust.OFFICIAL -> R.string.trust_official
        PackTrust.SIGNED -> R.string.trust_signed
        PackTrust.UNSIGNED -> R.string.trust_unsigned
    },
)

@Composable
private fun describe(result: ImportResult): String = when (result.status) {
    ImportStatus.INSTALLED -> stringResource(
        R.string.import_line,
        pluralStringResource(R.plurals.import_installed, result.soundsAdded, result.soundsAdded),
        trustLabel(result.trust),
    )
    ImportStatus.UPDATED -> stringResource(
        R.string.import_line,
        stringResource(
            R.string.import_updated,
            pluralStringResource(R.plurals.sounds, result.soundCount, result.soundCount),
            pluralStringResource(R.plurals.import_new_sounds, result.soundsAdded, result.soundsAdded),
        ),
        trustLabel(result.trust),
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
                PackError.FIELD_TOO_LONG -> R.string.pack_error_field_too_long
                PackError.UNSAFE_PATH -> R.string.pack_error_unsafe_path
                PackError.UNSUPPORTED_AUDIO -> R.string.pack_error_unsupported_audio
                PackError.UNKNOWN_PAGE -> R.string.pack_error_unknown_page
                PackError.FORBIDDEN_LICENSE -> R.string.pack_error_forbidden_license
                PackError.FILE_MISSING -> R.string.pack_error_file_missing
                PackError.TOO_LARGE -> R.string.pack_error_too_large
                PackError.HASH_MISMATCH -> R.string.pack_error_hash_mismatch
                PackError.SIGNATURE_INVALID -> R.string.pack_error_signature_invalid
                PackError.SIGNATURE_REQUIRED -> R.string.pack_error_signature_required
                PackError.CHANGED_DURING_IMPORT, PackError.IO, null -> R.string.pack_error_io
            },
        )
        val detail = if (result.detail.isBlank()) "" else " (${result.detail})"
        stringResource(R.string.import_refused, reason + detail)
    }
}
