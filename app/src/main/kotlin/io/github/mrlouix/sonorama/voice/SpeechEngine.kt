package io.github.mrlouix.sonorama.voice

import java.io.File

/** Écoute en cours ; [close] coupe le micro et libère le reconnaisseur. */
fun interface SpeechSession {
    fun close()
}

/** Mot reconnu et confiance du reconnaisseur, de 0 à 1. */
data class HeardWord(val text: String, val confidence: Float = 1f)

/** Suite de mots reconnus. */
data class Heard(val words: List<HeardWord>) {
    val text: String get() = words.joinToString(" ") { it.text }

    companion object {
        fun of(text: String, confidence: Float = 1f) =
            Heard(text.split(' ').filter { it.isNotEmpty() }.map { HeardWord(it, confidence) })
    }
}

/** Reconnaisseur à l'origine d'un résultat. */
enum class SpeechSource {
    MAIN,

    /** Signal abaissé, pour les voix d'enfant. */
    LOWERED,
}

/** Ce que demande une écoute. */
data class SpeechOptions(
    /** Le reconnaisseur ne cherche que ces expressions ; null : toute la phrase. */
    val grammar: List<String>? = null,
    /** Demande en plus le texte complet de chaque phrase. */
    val transcribe: Boolean = false,
    /** Ajoute un second reconnaisseur, sur le signal abaissé. */
    val childVoice: Boolean = false,
)

/** Reçoit le texte reconnu, sur un fil de fond. */
interface SpeechListener {
    /** Phrase en cours, susceptible d'être corrigée ; répétée tant qu'elle dure. */
    fun onPartial(heard: Heard, source: SpeechSource = SpeechSource.MAIN)

    /** Phrase terminée, parfois vide quand le texte en cours a été abandonné. */
    fun onFinal(heard: Heard, source: SpeechSource = SpeechSource.MAIN)

    /** Phrase complète, tous mots compris, et niveau moyen du micro en dBFS, pour la transcription. */
    fun onTranscript(heard: Heard, levelDb: Float)

    fun onError(error: Throwable)
}

/** Reconnaissance vocale ; abstraite pour que l'appli se teste sans micro ni code natif. */
interface SpeechEngine {
    /**
     * Ouvre le micro et commence la reconnaissance avec le modèle de [modelDir].
     */
    fun start(modelDir: File, options: SpeechOptions, listener: SpeechListener): SpeechSession

    /** Mots absents du vocabulaire du modèle : ils ne seront jamais reconnus. */
    fun unknownWords(modelDir: File, words: Collection<String>): Set<String>
}
