package io.github.mrlouix.sonorama

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mrlouix.sonorama.data.LibraryRepository
import io.github.mrlouix.sonorama.data.db.AppDatabase
import io.github.mrlouix.sonorama.data.db.CardEntity
import io.github.mrlouix.sonorama.data.db.PageCardEntity
import io.github.mrlouix.sonorama.data.db.PageEntity
import io.github.mrlouix.sonorama.data.db.SoundEntity
import io.github.mrlouix.sonorama.data.pack.PackFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Les anciennes pages intégrées se fondent dans les nouvelles sans perdre de carte. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MergedPagesTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Application>(),
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val repository = LibraryRepository(db, scope)
    private val dao = db.libraryDao()

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private val base = PackFormat.parse(
        """{"format":1,"id":"sonorama.base","version":4,"name":{"fr":"Base"},
           "pages":[{"id":"nature","name":{"fr":"Nature et météo"}},{"id":"ville","name":{"fr":"Ville et machines"}}],
           "sounds":[{"id":"birds","file":"a.ogg","name":{"fr":"Oiseaux"},"page":"nature","license":"CC0"},
                     {"id":"wind","file":"b.ogg","name":{"fr":"Vent"},"page":"nature","license":"CC0"},
                     {"id":"train","file":"c.ogg","name":{"fr":"Train"},"page":"ville","license":"CC0"}]}""",
    ).also(PackFormat::validate)

    private suspend fun addOldCard(cardId: String, soundId: String, page: String, volume: Int) {
        val packId = soundId.substringBefore('/')
        dao.upsertSounds(
            listOf(SoundEntity(soundId, packId, "x", "Son", "Son", "air", 50, "", "CC0", "", page, 0, true)),
        )
        dao.upsertCard(CardEntity(cardId, false, "Son", "Son", "air", volume, 0))
        dao.insertPageCards(listOf(PageCardEntity(page, cardId, 0)))
    }

    private suspend fun cards(page: String) = dao.cardsOfPage(page).map { it.cardId }

    @Test
    fun `updating the base pack merges the old pages`() = runBlocking {
        listOf("nature", "ville", "technologique", "meteo", "transports").forEachIndexed { index, id ->
            dao.upsertPage(PageEntity(id, id, id, "air", true, index))
        }
        dao.upsertPage(PageEntity("p:1", "Perso", "Perso", "air", false, 5))
        dao.insertPageCards(listOf(PageCardEntity("p:1", "s:sonorama.base/wind", 0)))
        addOldCard("s:sonorama.base/wind", "sonorama.base/wind", "meteo", 33)
        addOldCard("s:autre/tram", "autre/tram", "transports", 44)

        repository.installPack(base, builtIn = true) { "x" }

        assertEquals(listOf("nature", "ville", "p:1"), dao.pagesNow().map { it.id })
        assertEquals("Nature et météo", dao.page("nature")?.nameFr)
        assertEquals(setOf("s:sonorama.base/wind", "s:sonorama.base/birds"), cards("nature").toSet())
        assertEquals(setOf("s:autre/tram", "s:sonorama.base/train"), cards("ville").toSet())
        assertEquals(emptyList<String>(), cards("meteo"))
        assertEquals(listOf("s:sonorama.base/wind"), cards("p:1"))
        assertEquals(33, dao.card("s:sonorama.base/wind")?.volume)
        assertEquals("ville", dao.soundsOfPack("autre").single().pageId)
    }

    @Test
    fun `a pack naming an old page joins the new one`() = runBlocking {
        repository.installPack(base, builtIn = true) { "x" }
        val pack = PackFormat.parse(
            """{"format":1,"id":"autre","version":1,"name":{"fr":"Autre"},
               "pages":[{"id":"meteo","name":{"fr":"Météo"}}],
               "sounds":[{"id":"hail","file":"a.ogg","name":{"fr":"Grêle"},"page":"meteo","license":"CC0"},
                         {"id":"tram","file":"b.ogg","name":{"fr":"Tramway"},"page":"transports","license":"CC0"}]}""",
        ).also(PackFormat::validate)
        repository.installPack(pack, builtIn = false) { "x" }

        assertNull(dao.page("meteo"))
        assertEquals("s:autre/hail", cards("nature").last())
        assertEquals("s:autre/tram", cards("ville").last())
        assertEquals("Nature et météo", dao.page("nature")?.nameFr)
    }
}
