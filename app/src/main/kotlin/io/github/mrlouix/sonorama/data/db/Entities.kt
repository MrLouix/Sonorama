package io.github.mrlouix.sonorama.data.db

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
