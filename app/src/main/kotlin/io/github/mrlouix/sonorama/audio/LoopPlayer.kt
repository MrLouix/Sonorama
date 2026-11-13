package io.github.mrlouix.sonorama.audio

/** Un son joué en boucle. Toutes les méthodes sont appelées depuis le fil principal. */
interface LoopPlayer {
    /** Vrai quand le lecteur a chargé de quoi démarrer sans délai. */
    val isReady: Boolean

    /** Gain linéaire entre 0 et 1. */
    fun setGain(gain: Float)

    fun play()

    fun pause()

    fun release()
}

fun interface LoopPlayerFactory {
    /** Crée un lecteur préparé, à l'arrêt et à gain nul, pour le son situé à [uri]. */
    fun create(uri: String): LoopPlayer
}
