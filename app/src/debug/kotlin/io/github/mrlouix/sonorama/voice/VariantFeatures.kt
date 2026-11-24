package io.github.mrlouix.sonorama.voice

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.SonoramaApp
import io.github.mrlouix.sonorama.data.RecognitionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Version de débogage : transcription du texte entendu, pour repérer les sons à ajouter. */
object VariantFeatures {
    fun transcripts(context: Context): TranscriptSink? = TranscriptLog(context.applicationContext)

    /** Réglages réservés aux essais : transcription et mode de reconnaissance. */
    @Composable
    fun VoiceTestSettings() {
        val container = (LocalContext.current.applicationContext as SonoramaApp).container
        val log = container.transcripts as? TranscriptLog ?: return
        val settings by container.settings.collectAsState()
        val enabled by log.enabledFlow.collectAsState()
        val lines by log.lines.collectAsState()
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
            if (uri != null) scope.launch { log.exportTo(context, uri) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.debug_voice_title), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.debug_transcript_switch), Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = {
                    log.setEnabled(it)
                    container.voice.refresh()
                })
            }
            Text(
                pluralStringResource(R.plurals.debug_transcript_lines, lines, lines),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { export.launch("sonorama-transcription.csv") }, enabled = lines > 0) {
                    Text(stringResource(R.string.debug_transcript_export))
                }
                OutlinedButton(onClick = { scope.launch { log.clear() } }, enabled = lines > 0) {
                    Text(stringResource(R.string.debug_transcript_clear))
                }
            }
            Text(stringResource(R.string.debug_recognition_mode))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RecognitionMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.recognitionMode == mode,
                        onClick = { scope.launch { container.settingsStore.setRecognitionMode(mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index, RecognitionMode.entries.size),
                        icon = {},
                    ) {
                        Text(
                            stringResource(
                                if (mode == RecognitionMode.FULL) R.string.debug_mode_full else R.string.debug_mode_keywords,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Journal CSV du texte entendu : horodatage, phrase, cartes déclenchées. Texte seulement, jamais d'audio ;
 * il reste dans le dossier privé de l'appli jusqu'à son export par l'utilisateur.
 */
class TranscriptLog(context: Context) : TranscriptSink {
    private val prefs = context.getSharedPreferences("transcripts", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "transcripts.csv")
    private val writer = Executors.newSingleThreadExecutor()
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    val enabledFlow = MutableStateFlow(prefs.getBoolean(KEY, false))
    override val enabled: Boolean get() = enabledFlow.value

    private val _lines = MutableStateFlow(countLines())
    val lines: StateFlow<Int> = _lines.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY, value) }
        enabledFlow.value = value
    }

    override fun record(text: String, cardIds: List<String>) {
        val line = listOf(format.format(Date()), text, cardIds.joinToString(" ")).joinToString(",", transform = ::csv)
        writer.execute {
            if (!file.exists()) file.writeText(HEADER)
            file.appendText(line + "\n")
            _lines.value = countLines()
        }
    }

    suspend fun exportTo(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        writer.submit { file.delete() }.get()
        _lines.value = 0
    }

    private fun countLines(): Int = if (file.exists()) (file.readLines().size - 1).coerceAtLeast(0) else 0

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private companion object {
        const val KEY = "enabled"
        const val HEADER = "heure,texte,cartes\n"
    }
}
