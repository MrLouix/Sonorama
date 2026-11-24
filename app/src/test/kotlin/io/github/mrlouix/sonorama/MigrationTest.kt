package io.github.mrlouix.sonorama

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.mrlouix.sonorama.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Une base créée par la version 1 doit s'ouvrir avec la version courante sans perdre de données. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val name = "migration-test.db"

    /** Crée la base telle que l'écrivait la version [version], d'après le schéma exporté par Room. */
    private fun createDatabase(version: Int, fill: (SupportSQLiteDatabase) -> Unit) {
        val schema = Json.parseToJsonElement(
            File("schemas/io.github.mrlouix.sonorama.data.db.AppDatabase/$version.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                for (entity in schema.getValue("entities").jsonArray) {
                    val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                    db.execSQL(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    for (index in entity.jsonObject["indices"]?.jsonArray.orEmpty()) {
                        db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    }
                }
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                fill(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        )
        helper.writableDatabase.close()
    }

    @Test
    fun `version 1 database migrates and keeps its packs`() {
        context.deleteDatabase(name)
        createDatabase(1) { db ->
            db.execSQL("INSERT INTO packs (id, version, nameFr, nameEn, builtIn, installed) VALUES ('demo', 3, 'Démo', 'Demo', 0, 1)")
            db.execSQL(
                "INSERT INTO cards (id, composite, nameFr, nameEn, symbol, volume, createdAt) VALUES ('s:demo/rain', 0, 'Pluie', 'Rain', 'umbrella', 42, 1)",
            )
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.libraryDao()
            val pack = runBlocking { dao.pack("demo") }!!
            assertEquals(3, pack.version)
            assertNull(pack.signerKey)
            assertNull(pack.archiveSha256)
            assertEquals(42, runBlocking { dao.cards().first() }.single().volume)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
