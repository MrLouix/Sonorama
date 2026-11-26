package io.github.mrlouix.sonorama.voice

import kotlin.math.log10

/** Étire le signal dans le rapport 19/16, par interpolation linéaire, sans rupture entre deux tampons. */
class Stretcher(private val step: Double = 16.0 / 19.0) {
    /** Position du prochain échantillon dans le tampon à venir ; négative : entre le précédent et lui. */
    private var position = 0.0
    private var last = 0

    fun process(input: ShortArray): ShortArray {
        val out = ShortArray(((input.size - position) / step).toInt() + 2)
        var count = 0
        while (position <= input.size - 1) {
            val index = kotlin.math.floor(position).toInt()
            val from = if (index < 0) last else input[index].toInt()
            val to = if (index + 1 < input.size) input[index + 1].toInt() else from
            out[count++] = (from + (to - from) * (position - index)).toInt().toShort()
            position += step
        }
        position -= input.size
        if (input.isNotEmpty()) last = input.last().toInt()
        return out.copyOf(count)
    }
}

/** Niveau moyen du micro depuis la dernière lecture. */
class LevelMeter {
    private var sum = 0.0
    private var count = 0L

    fun add(buffer: ShortArray, size: Int) {
        for (i in 0 until size) sum += buffer[i].toDouble() * buffer[i]
        count += size
    }

    /** Niveau efficace en dBFS, puis remise à zéro. */
    fun take(): Float {
        val mean = if (count == 0L) 0.0 else sum / count / (32768.0 * 32768.0)
        sum = 0.0
        count = 0
        return (10 * log10(maxOf(mean, 1e-10))).toFloat()
    }
}
