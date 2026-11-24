package io.github.mrlouix.sonorama.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "packs")
data class PackEntity(
    @PrimaryKey val id: String,
    val version: Int,
    val nameFr: String,
    val nameEn: String,
    val builtIn: Boolean,
    val installed: Boolean,
    /** Empreinte de la clé qui a signé le pack, mémorisée au premier import ; null s'il n'est pas signé. */
    val signerKey: String? = null,
    /** Empreinte SHA-256 de l'archive ZIP importée ; null pour le pack de base. */
    val archiveSha256: String? = null,
)

@Entity(tableName = "sounds", indices = [Index("packId")])
data class SoundEntity(
    /** `<id du pack>/<id du son>`. */
    @PrimaryKey val id: String,
    val packId: String,
    val uri: String,
    val nameFr: String,
    val nameEn: String,
    val symbol: String,
    val defaultVolume: Int,
    val author: String,
    val license: String,
    val source: String,
    val pageId: String,
    val position: Int,
    val available: Boolean,
    /** Mots-clés du mode vocal déclarés par le pack, encodés par [Keywords]. */
    @ColumnInfo(defaultValue = "") val keywords: String = "",
)

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey val id: String,
    val composite: Boolean,
    val nameFr: String,
    val nameEn: String,
    val symbol: String,
    val volume: Int,
    val createdAt: Long,
    /** Mots-clés choisis par l'utilisateur, encodés par [Keywords] ; null : ceux du pack. */
    val keywords: String? = null,
)

@Entity(tableName = "card_sounds", primaryKeys = ["cardId", "position"], indices = [Index("soundId")])
data class CardSoundEntity(
    val cardId: String,
    val position: Int,
    val soundId: String,
    val relativeVolume: Int,
)

@Entity(tableName = "pages")
data class PageEntity(
    @PrimaryKey val id: String,
    val nameFr: String,
    val nameEn: String,
    val symbol: String,
    val builtIn: Boolean,
    val position: Int,
)

@Entity(tableName = "page_cards", primaryKeys = ["pageId", "cardId"])
data class PageCardEntity(
    val pageId: String,
    val cardId: String,
    val position: Int,
)
