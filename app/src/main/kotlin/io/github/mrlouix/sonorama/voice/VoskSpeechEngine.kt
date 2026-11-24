package io.github.mrlouix.sonorama.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Reconnaissance hors ligne par Vosk, sur le micro en mode « reconnaissance vocale ». */
class VoskSpeechEngine : SpeechEngine {
    /** Fonction de la bibliothèque native que l'API Java de Vosk n'expose pas. */
    private interface VoskExtra : Library {
        fun vosk_model_find_word(model: Pointer, word: String): Int
    }

    private val extra: VoskExtra by lazy { Native.load("vosk", VoskExtra::class.java) }

    /** Un modèle ouvert par langue, gardé pour la vie du processus : une écoute peut s'en servir à tout moment. */
    private val models = HashMap<File, Model>()

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
    }

    @Synchronized
    private fun model(dir: File): Model = models.getOrPut(dir) { Model(dir.absolutePath) }

    override fun unknownWords(modelDir: File, words: Collection<String>): Set<String> {
        val model = model(modelDir)
        return words.filterTo(HashSet()) { word ->
            // Le modèle français connaît les formes élidées (« d'été ») ; sinon, chaque partie doit être connue.
            val lower = word.lowercase()
            extra.vosk_model_find_word(model.pointer, lower) < 0 &&
                lower.split('\'', '’').filter { it.isNotEmpty() }.any { extra.vosk_model_find_word(model.pointer, it) < 0 }
        }
    }

    @SuppressLint("MissingPermission") // Le micro n'est ouvert qu'après l'accord de l'utilisateur.
    override fun start(modelDir: File, grammar: List<String>?, transcribe: Boolean, listener: SpeechListener): SpeechSession {
        val model = model(modelDir)
        val main = if (grammar != null) Recognizer(model, SAMPLE_RATE.toFloat(), grammarJson(grammar)) else Recognizer(model, SAMPLE_RATE.toFloat())
        val full = if (grammar != null && transcribe) Recognizer(model, SAMPLE_RATE.toFloat()) else null
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            SAMPLE_RATE / 5 * 2,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            main.close()
            full?.close()
            throw IllegalStateException("micro indisponible")
        }
        // Retire du signal une partie des sons que joue le téléphone, quand l'appareil le permet.
        val echo = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else {
            null
        }
        val running = AtomicBoolean(true)
        val worker = thread(name = "sonorama-voice", isDaemon = true) {
            try {
                record.startRecording()
                val buffer = ShortArray(SAMPLE_RATE / 10)
                var lastPartial = ""
                while (running.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) throw IllegalStateException("lecture du micro : $read")
                    if (read == 0) continue
                    if (main.acceptWaveForm(buffer, read)) {
                        val text = field(main.result, "text")
                        lastPartial = ""
                        if (text.isNotEmpty()) {
                            listener.onFinal(text)
                            if (full == null && grammar == null) listener.onTranscript(text)
                        }
                    } else {
                        val partial = field(main.partialResult, "partial")
                        if (partial.isNotEmpty() && partial != lastPartial) {
                            lastPartial = partial
                            listener.onPartial(partial)
                        }
                    }
                    if (full != null && full.acceptWaveForm(buffer, read)) {
                        field(full.result, "text").takeIf { it.isNotEmpty() }?.let(listener::onTranscript)
                    }
                }
            } catch (e: Throwable) {
                if (running.get()) listener.onError(e)
            } finally {
                runCatching { record.stop() }
                record.release()
                echo?.release()
                main.close()
                full?.close()
            }
        }
        return SpeechSession {
            running.set(false)
            worker.join(STOP_TIMEOUT_MS)
        }
    }

    private fun field(json: String, name: String): String =
        runCatching { Json.parseToJsonElement(json).jsonObject[name]?.jsonPrimitive?.content }.getOrNull().orEmpty().trim()

    /** Les mots restent en UTF-8 brut : Vosk ne décode pas les échappements `ê`. */
    private fun grammarJson(phrases: List<String>): String =
        JsonArray((phrases.map { it.lowercase() } + "[unk]").map(::JsonPrimitive)).toString()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val STOP_TIMEOUT_MS = 1000L
    }
}
