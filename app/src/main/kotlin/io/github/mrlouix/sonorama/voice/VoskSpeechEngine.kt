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
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
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
    override fun start(modelDir: File, options: SpeechOptions, listener: SpeechListener): SpeechSession {
        val model = model(modelDir)
        val grammar = options.grammar
        val main = recognizer(model, grammar)
        val full = if (grammar != null && options.transcribe) recognizer(model, null) else null
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
        val lowered = if (options.childVoice) LoweredRecognizer({ recognizer(model, grammar) }, listener) else null
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
                val level = LevelMeter()
                while (running.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) throw IllegalStateException("lecture du micro : $read")
                    if (read == 0) continue
                    level.add(buffer, read)
                    lowered?.offer(buffer, read)
                    if (main.acceptWaveForm(buffer, read)) {
                        val heard = finalOf(main)
                        // Même vide : le détecteur de mots-clés doit oublier la phrase abandonnée.
                        listener.onFinal(heard)
                        if (grammar == null) transcribe(heard, level, listener)
                    } else {
                        // Répété même sans changement : la stabilité d'un mot se mesure sur ces répétitions.
                        partialOf(main).takeIf { it.words.isNotEmpty() }?.let { listener.onPartial(it) }
                    }
                    if (full != null && full.acceptWaveForm(buffer, read)) transcribe(finalOf(full), level, listener)
                }
            } catch (e: Throwable) {
                if (running.get()) listener.onError(e)
            } finally {
                runCatching { record.stop() }
                record.release()
                echo?.release()
                lowered?.close()
                main.close()
                full?.close()
            }
        }
        return SpeechSession {
            running.set(false)
            worker.join(STOP_TIMEOUT_MS)
        }
    }

    private fun recognizer(model: Model, grammar: List<String>?): Recognizer {
        val rate = SAMPLE_RATE.toFloat()
        val recognizer = if (grammar != null) Recognizer(model, rate, grammarJson(grammar)) else Recognizer(model, rate)
        recognizer.setWords(true)
        recognizer.setPartialWords(true)
        recognizer.setEndpointerDelays(SILENCE_BEFORE_S, SILENCE_AFTER_S, MAX_PHRASE_S)
        return recognizer
    }

    private fun transcribe(heard: Heard, level: LevelMeter, listener: SpeechListener) {
        val db = level.take()
        if (heard.words.isNotEmpty()) listener.onTranscript(heard, db)
    }

    /**
     * Reconnaisseur des voix d'enfant, sur son propre fil : étiré de 16 à 19 kHz puis lu comme s'il était à
     * 16 kHz, le signal a ses fréquences multipliées par 0,84, plus proches d'une voix d'adulte.
     */
    private class LoweredRecognizer(private val create: () -> Recognizer, private val listener: SpeechListener) {
        private val queue = ArrayBlockingQueue<ShortArray>(QUEUE_BUFFERS)
        private val running = AtomicBoolean(true)
        private val worker = thread(name = "sonorama-voice-lowered", isDaemon = true) {
            runCatching(::run).onFailure { if (running.get()) listener.onError(it) }
        }

        /** Un téléphone trop lent perd du son ici, jamais dans le reconnaisseur principal. */
        fun offer(buffer: ShortArray, count: Int) {
            queue.offer(buffer.copyOf(count))
        }

        fun close() {
            running.set(false)
            worker.join(STOP_TIMEOUT_MS)
        }

        private fun run() {
            var recognizer = create()
            try {
                val stretcher = Stretcher()
                var heardSamples = 0L
                while (running.get()) {
                    val input = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                    val samples = stretcher.process(input)
                    heardSamples += samples.size
                    if (recognizer.acceptWaveForm(samples, samples.size)) {
                        listener.onFinal(finalOf(recognizer), SpeechSource.LOWERED)
                        // Reconnaisseur neuf : l'adaptation à la voix de l'adulte qui lit est oubliée.
                        if (heardSamples > RENEW_AFTER_S * SAMPLE_RATE) {
                            heardSamples = 0
                            val fresh = create()
                            recognizer.close()
                            recognizer = fresh
                        }
                    } else {
                        partialOf(recognizer).takeIf { it.words.isNotEmpty() }?.let { listener.onPartial(it, SpeechSource.LOWERED) }
                    }
                }
            } finally {
                recognizer.close()
            }
        }
    }

    /** Les mots restent en UTF-8 brut : Vosk ne décode pas les échappements `ê`. */
    private fun grammarJson(phrases: List<String>): String =
        JsonArray((phrases.map { it.lowercase() } + "[unk]").map(::JsonPrimitive)).toString()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val STOP_TIMEOUT_MS = 1000L
        const val POLL_MS = 100L

        /** Tampons de 0,1 s en attente du reconnaisseur abaissé. */
        const val QUEUE_BUFFERS = 30
        const val RENEW_AFTER_S = 30L

        // Fin de phrase : silence avant le premier mot, silence après un mot (0,5 s par défaut), durée maximale.
        // Un silence plus long évite de découper une lecture en segments de deux ou trois mots.
        const val SILENCE_BEFORE_S = 5f
        const val SILENCE_AFTER_S = 1f
        const val MAX_PHRASE_S = 20f

        fun finalOf(recognizer: Recognizer): Heard = parse(recognizer.result, "text", "result")

        fun partialOf(recognizer: Recognizer): Heard = parse(recognizer.partialResult, "partial", "partial_result")

        /** Mots et confiances d'un résultat de Vosk ; sans détail par mot, le texte seul, tenu pour sûr. */
        fun parse(json: String, textField: String, wordsField: String): Heard = runCatching {
            val result = Json.parseToJsonElement(json).jsonObject
            val words = result[wordsField]?.jsonArray
                ?: return Heard.of(result[textField]?.jsonPrimitive?.content.orEmpty().trim())
            Heard(
                words.map { it.jsonObject }.map {
                    HeardWord(it.getValue("word").jsonPrimitive.content, it["conf"]?.jsonPrimitive?.floatOrNull ?: 1f)
                },
            )
        }.getOrDefault(Heard(emptyList()))
    }
}
