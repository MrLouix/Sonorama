package io.github.mrlouix.sonorama.data

/** Texte disponible en français et en anglais. */
data class LocalizedName(val fr: String, val en: String) {
    fun get(french: Boolean): String = if (french) fr.ifBlank { en } else en.ifBlank { fr }
}

data class Pack(
    val id: String,
    val version: Int,
    val name: LocalizedName,
    val builtIn: Boolean,
    val installed: Boolean,
    val soundCount: Int,
)

data class Sound(
    val id: String,
    val packId: String,
    val uri: String,
    val name: LocalizedName,
    val symbol: String,
    val defaultVolume: Int,
    val author: String,
    val license: String,
    val source: String,
    val pageId: String,
    val available: Boolean,
)

data class CardSound(val soundId: String, val relativeVolume: Int)

data class Card(
    val id: String,
    val composite: Boolean,
    val name: LocalizedName,
    val symbol: String,
    val volume: Int,
    val sounds: List<CardSound>,
    /** Faux si un des sons manque (pack désinstallé). */
    val available: Boolean,
    /** Page thématique de la carte ; null pour les cartes composées (« Mes cartes »). */
    val themePageId: String?,
    /** Pack d'origine d'une carte simple, pour proposer de le réimporter. */
    val packId: String?,
)

data class Page(
    val id: String,
    val name: LocalizedName,
    val symbol: String,
    val builtIn: Boolean,
    val cardIds: List<String>,
)

/** Photographie immuable de toute la bibliothèque. */
data class Library(
    val loaded: Boolean = false,
    val packs: List<Pack> = emptyList(),
    val sounds: Map<String, Sound> = emptyMap(),
    val cards: Map<String, Card> = emptyMap(),
    /** Pages visibles, dans l'ordre des onglets. */
    val pages: List<Page> = emptyList(),
) {
    fun page(id: String): Page? = pages.firstOrNull { it.id == id }

    val compositeCards: List<Card> get() = cards.values.filter { it.composite }
}

object Limits {
    const val PAGE_NAME_MAX = 30
    const val CARD_NAME_MAX = 24
    const val PAGE_CARDS_MAX = 24
    const val COMPOSITE_SOUNDS_MIN = 2
    const val COMPOSITE_SOUNDS_MAX = 12
}

/** Identifiant de la carte simple associée à un son. */
fun simpleCardId(soundId: String): String = "s:$soundId"
