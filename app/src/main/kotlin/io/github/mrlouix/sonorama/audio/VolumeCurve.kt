package io.github.mrlouix.sonorama.audio

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Courbe perceptive : convertit un volume affiché (0 à 100 %) en gain linéaire (0 à 1). */
object VolumeCurve {
    const val MIN_VOLUME = 0
    const val MAX_VOLUME = 100
    const val DEFAULT_VOLUME = 70

    // Environ 40 dB de dynamique entre 1 % et 100 %.
    private const val K = 4.6f
    private val NORM = exp(K) - 1f

    /** [level] entre 0 et 1 ; résultat entre 0 et 1, exponentiel, nul en 0. */
    fun gain(level: Float): Float {
        val x = level.coerceIn(0f, 1f)
        return (exp(K * x) - 1f) / NORM
    }

    fun gainForPercent(percent: Int): Float = gain(percent / 100f)

    /** Réciproque de [gainForPercent]. */
    fun percentForGain(gain: Float): Int {
        val g = gain.coerceIn(0f, 1f)
        return (ln(g * NORM + 1f) / K * 100f).roundToInt().coerceIn(MIN_VOLUME, MAX_VOLUME)
    }

    /** Volume équivalent à deux volumes appliqués l'un après l'autre (carte × son). */
    fun combine(percentA: Int, percentB: Int): Int =
        percentForGain(gainForPercent(percentA) * gainForPercent(percentB))

    /** Courbe appliquée aux fondus : départ doux, arrivée franche. */
    fun fade(factor: Float): Float {
        val x = factor.coerceIn(0f, 1f)
        return x * x
    }
}
