package io.github.mrlouix.sonorama.voice

import io.github.mrlouix.sonorama.data.Card
import io.github.mrlouix.sonorama.data.Keywords

/** Mot-clé entendu et cartes qu'il déclenche. */
data class KeywordHit(val keyword: String, val cardIds: List<String>)

/**
 * Repère les mots-clés dans le texte reconnu. Les résultats partiels d'une même phrase se répètent
 * et se corrigent : un mot-clé ne déclenche que lorsque son nombre d'occurrences dans la phrase augmente.
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

    val isEmpty: Boolean get() = phrases.isEmpty()

    /** Mots-clés à donner au reconnaisseur limité, tels que saisis. */
    val keywords: List<String> get() = phrases.values.map { it.first }

    fun onPartial(text: String): List<KeywordHit> = scan(text)

    /** Fin de phrase : les compteurs repartent de zéro pour la suivante. */
    fun onFinal(text: String): List<KeywordHit> = scan(text).also { seen.clear() }

    private fun scan(text: String): List<KeywordHit> {
        val words = Keywords.normalize(text).split(' ').filter { it.isNotEmpty() && it != "unk" }
        if (words.isEmpty()) return emptyList()
        val hits = ArrayList<KeywordHit>()
        for ((tokens, value) in phrases) {
            val count = occurrences(words, tokens)
            if (count > (seen[tokens] ?: 0)) {
                seen[tokens] = count
                hits += KeywordHit(value.first, value.second)
            }
        }
        return hits
    }

    private fun occurrences(words: List<String>, tokens: List<String>): Int {
        var count = 0
        var i = 0
        while (i + tokens.size <= words.size) {
            if (words.subList(i, i + tokens.size) == tokens) {
                count++
                i += tokens.size
            } else {
                i++
            }
        }
        return count
    }

    companion object {
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
