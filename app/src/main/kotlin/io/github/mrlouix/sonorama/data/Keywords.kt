package io.github.mrlouix.sonorama.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.text.Normalizer

/** Mots-clés du mode vocal : stockage en base et forme normalisée pour la comparaison. */
object Keywords {
    const val MAX_PER_LANGUAGE = 30
    const val MAX_LENGTH = 40

    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    private val json = Json { ignoreUnknownKeys = true }
    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /** Chaîne vide pour une carte sans mots-clés, sauf avec [keepEmpty] (choix explicite de l'utilisateur). */
    fun encode(map: Map<String, List<String>>, keepEmpty: Boolean = false): String =
        if (map.isEmpty() && !keepEmpty) "" else json.encodeToString(serializer, map)

    fun decode(text: String): Map<String, List<String>> =
        if (text.isBlank()) emptyMap() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyMap())

    /** Retire les doublons et les mots vides, borne la longueur et le nombre. */
    fun clean(words: List<String>): List<String> =
        words.map { it.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinctBy(::normalize)
            .take(MAX_PER_LANGUAGE)

    /** Minuscules, sans accents, mots séparés par une espace : « Forêt  d'Été » → « foret d ete ». */
    fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
            .split(SEPARATORS)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
