package io.github.mrlouix.sonorama.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Export et import d'une page personnalisée sous forme de fichier JSON. */
object PageExchange {
    const val FORMAT = 1
    const val TYPE = "sonorama-page"
    const val MIME = "application/json"

    @Serializable
    data class PageFile(
        val type: String = TYPE,
        val format: Int = FORMAT,
        val name: String,
        val symbol: String,
        val cards: List<CardRef>,
    )

    /** Soit une carte simple ([sound]), soit une carte composée ([composite]). */
    @Serializable
    data class CardRef(val sound: String? = null, val composite: CompositeRef? = null)

    @Serializable
    data class CompositeRef(val name: String, val symbol: String, val volume: Int, val sounds: List<SoundRef>)

    @Serializable
    data class SoundRef(val sound: String, val volume: Int)

    class InvalidPageFile(message: String) : Exception(message)

    data class ImportReport(val pageId: String, val missingCards: Int)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun export(page: Page, library: Library): String {
        val cards = page.cardIds.mapNotNull { library.cards[it] }.map { card ->
            if (card.composite) {
                CardRef(
                    composite = CompositeRef(
                        name = card.name.fr,
                        symbol = card.symbol,
                        volume = card.volume,
                        sounds = card.sounds.map { SoundRef(it.soundId, it.relativeVolume) },
                    ),
                )
            } else {
                CardRef(sound = card.sounds.firstOrNull()?.soundId)
            }
        }
        return json.encodeToString(PageFile.serializer(), PageFile(name = page.name.fr, symbol = page.symbol, cards = cards))
    }

    fun parse(text: String): PageFile {
        val file = try {
            json.decodeFromString(PageFile.serializer(), text)
        } catch (e: Exception) {
            throw InvalidPageFile(e.message.orEmpty())
        }
        if (file.type != TYPE || file.format != FORMAT) throw InvalidPageFile("type ou format inconnu")
        if (file.name.isBlank()) throw InvalidPageFile("nom manquant")
        return file
    }

    /**
     * Crée la page décrite par [file]. Les cartes simples dont le son n'est pas connu
     * sont ignorées et comptées ; les cartes composées sont recréées.
     */
    suspend fun import(file: PageFile, repository: LibraryRepository): ImportReport {
        val library = repository.library.value
        var missing = 0
        val cardIds = ArrayList<String>()
        for (ref in file.cards.take(Limits.PAGE_CARDS_MAX)) {
            val composite = ref.composite
            val sound = ref.sound
            when {
                composite != null -> {
                    val sounds = composite.sounds
                        .filter { it.sound in library.sounds }
                        .map { CardSound(it.sound, it.volume) }
                    if (sounds.size < Limits.COMPOSITE_SOUNDS_MIN) {
                        missing++
                    } else {
                        cardIds += repository.saveComposite(null, composite.name, composite.symbol, sounds, composite.volume.coerceIn(0, 100))
                    }
                }
                sound != null && simpleCardId(sound) in library.cards -> cardIds += simpleCardId(sound)
                else -> missing++
            }
        }
        val pageId = repository.savePage(null, file.name, file.symbol, cardIds)
        return ImportReport(pageId, missing)
    }
}
