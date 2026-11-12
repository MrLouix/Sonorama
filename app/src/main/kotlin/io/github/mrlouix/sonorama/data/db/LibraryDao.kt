package io.github.mrlouix.sonorama.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    // ------------------------------------------------------------------ flux
    @Query("SELECT * FROM packs ORDER BY builtIn DESC, nameFr")
    fun packs(): Flow<List<PackEntity>>

    @Query("SELECT * FROM sounds ORDER BY packId, position")
    fun sounds(): Flow<List<SoundEntity>>

    @Query("SELECT * FROM cards ORDER BY createdAt, id")
    fun cards(): Flow<List<CardEntity>>

    @Query("SELECT * FROM card_sounds ORDER BY cardId, position")
    fun cardSounds(): Flow<List<CardSoundEntity>>

    @Query("SELECT * FROM pages ORDER BY position, id")
    fun pages(): Flow<List<PageEntity>>

    @Query("SELECT * FROM page_cards ORDER BY pageId, position")
    fun pageCards(): Flow<List<PageCardEntity>>

    // ----------------------------------------------------------------- packs
    @Query("SELECT * FROM packs WHERE id = :id")
    suspend fun pack(id: String): PackEntity?

    @Upsert
    suspend fun upsertPack(pack: PackEntity)

    @Query("UPDATE packs SET installed = 0 WHERE id = :id")
    suspend fun markPackUninstalled(id: String)

    // ------------------------------------------------------------------ sons
    @Query("SELECT * FROM sounds WHERE packId = :packId")
    suspend fun soundsOfPack(packId: String): List<SoundEntity>

    @Upsert
    suspend fun upsertSounds(sounds: List<SoundEntity>)

    @Query("UPDATE sounds SET available = 0 WHERE packId = :packId")
    suspend fun markSoundsUnavailable(packId: String)

    // ---------------------------------------------------------------- cartes
    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun card(id: String): CardEntity?

    @Upsert
    suspend fun upsertCard(card: CardEntity)

    @Query("UPDATE cards SET volume = :volume WHERE id = :id")
    suspend fun setCardVolume(id: String, volume: Int)

    @Query("UPDATE cards SET nameFr = :nameFr, nameEn = :nameEn, symbol = :symbol WHERE id = :id")
    suspend fun describeCard(id: String, nameFr: String, nameEn: String, symbol: String)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun deleteCard(id: String)

    @Query("DELETE FROM card_sounds WHERE cardId = :cardId")
    suspend fun deleteCardSounds(cardId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCardSounds(sounds: List<CardSoundEntity>)

    @Query("DELETE FROM page_cards WHERE cardId = :cardId")
    suspend fun removeCardFromPages(cardId: String)

    // ----------------------------------------------------------------- pages
    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun page(id: String): PageEntity?

    @Query("SELECT * FROM pages ORDER BY position, id")
    suspend fun pagesNow(): List<PageEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM pages")
    suspend fun lastPagePosition(): Int

    @Upsert
    suspend fun upsertPage(page: PageEntity)

    @Upsert
    suspend fun upsertPages(pages: List<PageEntity>)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deletePage(id: String)

    @Query("SELECT * FROM page_cards WHERE pageId = :pageId ORDER BY position")
    suspend fun cardsOfPage(pageId: String): List<PageCardEntity>

    @Query("DELETE FROM page_cards WHERE pageId = :pageId")
    suspend fun clearPage(pageId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPageCards(cards: List<PageCardEntity>)
}
