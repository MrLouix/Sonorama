package io.github.mrlouix.sonorama.data

import androidx.room.withTransaction
import io.github.mrlouix.sonorama.audio.CardSpec
import io.github.mrlouix.sonorama.audio.SoundSpec
import io.github.mrlouix.sonorama.data.db.AppDatabase
import io.github.mrlouix.sonorama.data.db.CardEntity
import io.github.mrlouix.sonorama.data.db.CardSoundEntity
import io.github.mrlouix.sonorama.data.db.PackEntity
import io.github.mrlouix.sonorama.data.db.PageCardEntity
import io.github.mrlouix.sonorama.data.db.PageEntity
import io.github.mrlouix.sonorama.data.db.SoundEntity
import io.github.mrlouix.sonorama.data.pack.ManifestSound
import io.github.mrlouix.sonorama.data.pack.PackManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/** Source unique de la bibliothèque : packs, sons, cartes et pages. */
class LibraryRepository(private val db: AppDatabase, scope: CoroutineScope) {
    private val dao = db.libraryDao()

    private data class Tables(
        val packs: List<PackEntity>,
        val sounds: List<SoundEntity>,
        val cards: List<CardEntity>,
        val cardSounds: List<CardSoundEntity>,
        val pages: List<PageEntity>,
    )

    val library: StateFlow<Library> =
        combine(dao.packs(), dao.sounds(), dao.cards(), dao.cardSounds(), dao.pages(), ::Tables)
            .combine(dao.pageCards()) { tables, pageCards -> buildLibrary(tables, pageCards) }
            .stateIn(scope, SharingStarted.Eagerly, Library())

    /** Ce que le moteur doit savoir pour jouer une carte ; null si elle est injouable. */
    fun cardSpec(cardId: String): CardSpec? {
        val lib = library.value
        val card = lib.cards[cardId]?.takeIf { it.available } ?: return null
        val sounds = card.sounds.map { SoundSpec(lib.sounds.getValue(it.soundId).uri, it.relativeVolume) }
        return CardSpec(card.id, card.volume, sounds)
    }

    private fun buildLibrary(t: Tables, pageCards: List<PageCardEntity>): Library {
        val sounds = t.sounds.associate { s ->
            s.id to Sound(
                id = s.id, packId = s.packId, uri = s.uri, name = LocalizedName(s.nameFr, s.nameEn),
                symbol = s.symbol, defaultVolume = s.defaultVolume, author = s.author,
                license = s.license, source = s.source, pageId = s.pageId, available = s.available,
            )
        }
        val soundsByCard = t.cardSounds.groupBy { it.cardId }
        val cards = LinkedHashMap<String, Card>()
        for (c in t.cards) {
            val parts = soundsByCard[c.id].orEmpty().map { CardSound(it.soundId, it.relativeVolume) }
            val first = parts.firstOrNull()?.let { sounds[it.soundId] }
            cards[c.id] = Card(
                id = c.id, composite = c.composite, name = LocalizedName(c.nameFr, c.nameEn),
                symbol = c.symbol, volume = c.volume, sounds = parts,
                available = parts.isNotEmpty() && parts.all { sounds[it.soundId]?.available == true },
                themePageId = if (c.composite) null else first?.pageId,
                packId = if (c.composite) null else first?.packId,
            )
        }
        val cardsByPage = pageCards.groupBy { it.pageId }
        val pages = t.pages.mapNotNull { p ->
            val ids = cardsByPage[p.id].orEmpty().map { it.cardId }.filter { id ->
                val card = cards[id] ?: return@filter false
                !p.builtIn || card.available
            }
            if (p.builtIn && ids.isEmpty()) return@mapNotNull null
            Page(p.id, LocalizedName(p.nameFr, p.nameEn), p.symbol, p.builtIn, ids)
        }
        val counts = t.sounds.filter { it.available }.groupingBy { it.packId }.eachCount()
        val packs = t.packs.map {
            Pack(it.id, it.version, LocalizedName(it.nameFr, it.nameEn), it.builtIn, it.installed, counts[it.id] ?: 0)
        }
        return Library(loaded = true, packs = packs, sounds = sounds, cards = cards, pages = pages)
    }

    // ------------------------------------------------------------------ packs

    suspend fun installedPackVersion(packId: String): Int? =
        dao.pack(packId)?.takeIf { it.installed }?.version

    /**
     * Enregistre un pack et crée ses cartes et ses pages. Les volumes, pages personnalisées
     * et cartes composées existants sont conservés. Renvoie le nombre de sons nouveaux.
     */
    suspend fun installPack(manifest: PackManifest, builtIn: Boolean, uriOf: (ManifestSound) -> String): Int =
        db.withTransaction {
            val packId = manifest.id
            val known = dao.soundsOfPack(packId).associateBy { it.id }
            dao.upsertPack(PackEntity(packId, manifest.version, manifest.name.fr, manifest.name.en, builtIn, true))
            dao.markSoundsUnavailable(packId)
            for (page in manifest.pages) {
                if (dao.page(page.id) == null) {
                    dao.upsertPage(
                        PageEntity(page.id, page.name.fr, page.name.en, page.symbol, true, dao.lastPagePosition() + 1),
                    )
                }
            }
            var added = 0
            val now = System.currentTimeMillis()
            val entities = manifest.sounds.mapIndexed { index, s ->
                val id = "$packId/${s.id}"
                if (id !in known) added++
                SoundEntity(
                    id = id, packId = packId, uri = uriOf(s), nameFr = s.name.fr, nameEn = s.name.en,
                    symbol = s.symbol, defaultVolume = s.defaultVolume.coerceIn(0, 100), author = s.author,
                    license = s.license, source = s.source, pageId = s.page, position = index, available = true,
                )
            }
            dao.upsertSounds(entities)
            for ((index, sound) in entities.withIndex()) {
                val cardId = simpleCardId(sound.id)
                if (dao.card(cardId) == null) {
                    dao.upsertCard(
                        CardEntity(cardId, false, sound.nameFr, sound.nameEn, sound.symbol, sound.defaultVolume, now + index),
                    )
                } else {
                    dao.describeCard(cardId, sound.nameFr, sound.nameEn, sound.symbol)
                }
                dao.deleteCardSounds(cardId)
                dao.insertCardSounds(listOf(CardSoundEntity(cardId, 0, sound.id, 100)))
            }
            for ((pageId, pageSounds) in entities.groupBy { it.pageId }) {
                if (dao.page(pageId) == null) continue
                val present = dao.cardsOfPage(pageId)
                val presentIds = present.map { it.cardId }.toSet()
                var next = (present.maxOfOrNull { it.position } ?: -1) + 1
                val missing = pageSounds.map { simpleCardId(it.id) }.filter { it !in presentIds }
                dao.insertPageCards(missing.map { PageCardEntity(pageId, it, next++) })
            }
            added
        }

    /** Marque le pack désinstallé ; ses cartes restent, grisées, sur les pages personnalisées. */
    suspend fun uninstallPack(packId: String) = db.withTransaction {
        dao.markPackUninstalled(packId)
        dao.markSoundsUnavailable(packId)
    }

    // ----------------------------------------------------------------- cartes

    suspend fun setCardVolume(cardId: String, volume: Int) = dao.setCardVolume(cardId, volume.coerceIn(0, 100))

    /** Crée ([id] nul) ou met à jour une carte composée. Renvoie son identifiant. */
    suspend fun saveComposite(id: String?, name: String, symbol: String, sounds: List<CardSound>, volume: Int? = null): String =
        db.withTransaction {
            val cardId = id ?: "c:${UUID.randomUUID()}"
            val existing = dao.card(cardId)
            val label = name.trim().take(Limits.CARD_NAME_MAX)
            dao.upsertCard(
                CardEntity(
                    id = cardId, composite = true, nameFr = label, nameEn = label, symbol = symbol,
                    volume = volume ?: existing?.volume ?: 100,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                ),
            )
            dao.deleteCardSounds(cardId)
            dao.insertCardSounds(
                sounds.take(Limits.COMPOSITE_SOUNDS_MAX).mapIndexed { index, s ->
                    CardSoundEntity(cardId, index, s.soundId, s.relativeVolume.coerceIn(0, 100))
                },
            )
            cardId
        }

    /** Supprime une carte composée et la retire de toutes les pages. */
    suspend fun deleteCard(cardId: String) = db.withTransaction {
        dao.removeCardFromPages(cardId)
        dao.deleteCardSounds(cardId)
        dao.deleteCard(cardId)
    }

    // ------------------------------------------------------------------ pages

    /** Crée ([id] nul) ou met à jour une page personnalisée. Renvoie son identifiant. */
    suspend fun savePage(id: String?, name: String, symbol: String, cardIds: List<String>): String =
        db.withTransaction {
            val pageId = id ?: "p:${UUID.randomUUID()}"
            val existing = dao.page(pageId)
            require(existing?.builtIn != true) { "Les pages intégrées ne sont pas modifiables" }
            val label = name.trim().take(Limits.PAGE_NAME_MAX)
            dao.upsertPage(
                PageEntity(pageId, label, label, symbol, false, existing?.position ?: (dao.lastPagePosition() + 1)),
            )
            dao.clearPage(pageId)
            dao.insertPageCards(
                cardIds.distinct().take(Limits.PAGE_CARDS_MAX).mapIndexed { index, cardId ->
                    PageCardEntity(pageId, cardId, index)
                },
            )
            pageId
        }

    suspend fun deletePage(pageId: String) = db.withTransaction {
        if (dao.page(pageId)?.builtIn == false) {
            dao.clearPage(pageId)
            dao.deletePage(pageId)
        }
    }

    /** Déplace un onglet d'un cran vers la gauche ([delta] = -1) ou la droite (+1). */
    suspend fun movePage(pageId: String, delta: Int) = db.withTransaction {
        val visible = library.value.pages.map { it.id }.toMutableList()
        val from = visible.indexOf(pageId)
        val to = from + delta
        if (from < 0 || to !in visible.indices) return@withTransaction
        visible.add(to, visible.removeAt(from))
        val all = dao.pagesNow()
        val order = visible + all.map { it.id }.filter { it !in visible }
        dao.upsertPages(all.map { it.copy(position = order.indexOf(it.id)) })
    }
}
