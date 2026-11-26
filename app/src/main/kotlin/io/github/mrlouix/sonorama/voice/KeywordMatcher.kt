package io.github.mrlouix.sonorama.voice

import io.github.mrlouix.sonorama.data.Card
import io.github.mrlouix.sonorama.data.Keywords

/** Mot-clé entendu, cartes qu'il déclenche et confiance du moins sûr de ses mots. */
data class KeywordHit(val keyword: String, val cardIds: List<String>, val confidence: Float = 1f)

/**
 * Repère les mots-clés dans le texte reconnu. Les résultats partiels d'une même phrase se répètent
 * et se corrigent : un mot-clé ne déclenche que lorsque son nombre d'occurrences dans la phrase augmente.
 * Une occurrence compte si le reconnaisseur en est assez sûr, ou si elle tient sur plusieurs résultats de
 * suite : une hypothèse fugace et peu sûre ne déclenche rien.
 */
class KeywordMatcher(entries: Map<String, List<String>>) {
    /** Forme normalisée → (forme affichée, cartes). */
    private val phrases: Map<List<String>, Pair<String, List<String>>> =
        entries.entries
            .mapNotNull { (keyword, cards) ->
                val tokens = Keywords.normalize(keyword).split(' ').filter { it.isNotEmpty() }
                if (tokens.isEmpty()) null else tokens to (keyword to cards)
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.first().first to values.flatMap { it.second }.distinct() }

    private val seen = HashMap<List<String>, Int>()

    /** Nombre de résultats de suite qui contiennent chaque occurrence d'une expression. */
    private val streaks = HashMap<List<String>, List<Int>>()

    val isEmpty: Boolean get() = phrases.isEmpty()

    /** Mots-clés à donner au reconnaisseur limité, tels que saisis. */
    val keywords: List<String> get() = phrases.values.map { it.first }

    fun onPartial(heard: Heard): List<KeywordHit> = scan(heard)

    /** Fin de phrase : les compteurs repartent de zéro pour la suivante. */
    fun onFinal(heard: Heard): List<KeywordHit> = scan(heard).also {
        seen.clear()
        streaks.clear()
    }

    private fun scan(heard: Heard): List<KeywordHit> {
        val words = heard.words.flatMap { word ->
            Keywords.normalize(word.text).split(' ').filter { it.isNotEmpty() && it != "unk" }.map { HeardWord(it, word.confidence) }
        }
        val texts = words.map { it.text }
        val hits = ArrayList<KeywordHit>()
        for ((tokens, value) in phrases) {
            val confidences = occurrences(words, texts, tokens)
            if (confidences.isEmpty()) {
                streaks.remove(tokens)
                continue
            }
            val before = streaks[tokens].orEmpty()
            val streak = confidences.indices.map { (before.getOrNull(it) ?: 0) + 1 }
            streaks[tokens] = streak
            val accepted = confidences.indices.filter { confidences[it] >= MIN_CONFIDENCE || streak[it] >= STABLE_RESULTS }
            if (accepted.size > (seen[tokens] ?: 0)) {
                seen[tokens] = accepted.size
                hits += KeywordHit(value.first, value.second, confidences[accepted.last()])
            }
        }
        return hits
    }

    /** Confiance de chaque occurrence de [tokens] dans la phrase. */
    private fun occurrences(words: List<HeardWord>, texts: List<String>, tokens: List<String>): List<Float> {
        val found = ArrayList<Float>()
        var i = 0
        while (i + tokens.size <= words.size) {
            if (texts.subList(i, i + tokens.size) == tokens) {
                found += (i until i + tokens.size).minOf { words[it].confidence }
                i += tokens.size
            } else {
                i++
            }
        }
        return found
    }

    companion object {
        /** Confiance qui déclenche aussitôt. */
        const val MIN_CONFIDENCE = 0.6f

        /** Résultats de suite qui déclenchent malgré une confiance faible : 0,4 s au rythme du moteur. */
        const val STABLE_RESULTS = 4

        /** Mots-clés des cartes jouables de [cards], dans [language]. */
        fun forCards(cards: List<Card>, language: String): KeywordMatcher {
            val entries = LinkedHashMap<String, MutableList<String>>()
            for (card in cards) {
                if (!card.available) continue
                for (keyword in card.keywords[language].orEmpty()) {
                    entries.getOrPut(keyword) { ArrayList() } += card.id
                }
            }
            return KeywordMatcher(entries)
        }
    }
}
