package io.github.mrlouix.sonorama.voice

import java.io.File

/** Écoute en cours ; [close] coupe le micro et libère le reconnaisseur. */
fun interface SpeechSession {
    fun close()
}

/** Reçoit le texte reconnu, sur un fil de fond. */
interface SpeechListener {
    /** Phrase en cours, susceptible d'être corrigée. */
    fun onPartial(text: String)

    /** Phrase terminée. */
    fun onFinal(text: String)

    /** Phrase complète, tous mots compris, pour la transcription. */
    fun onTranscript(text: String)

    fun onError(error: Throwable)
}

/** Reconnaissance vocale ; abstraite pour que l'appli se teste sans micro ni code natif. */
interface SpeechEngine {
    /**
     * Ouvre le micro et commence la reconnaissance avec le modèle de [modelDir]. Avec [grammar], le
     * reconnaisseur ne cherche que ces expressions ; [transcribe] demande en plus le texte complet.
     */
    fun start(modelDir: File, grammar: List<String>?, transcribe: Boolean, listener: SpeechListener): SpeechSession

    /** Mots absents du vocabulaire du modèle : ils ne seront jamais reconnus. */
    fun unknownWords(modelDir: File, words: Collection<String>): Set<String>
}
