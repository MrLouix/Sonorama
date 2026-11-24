package io.github.mrlouix.sonorama.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PackEntity::class,
        SoundEntity::class,
        CardEntity::class,
        CardSoundEntity::class,
        PageEntity::class,
        PageCardEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        const val NAME = "sonorama.db"

        /** Version 2 : signature des packs (clé du signataire, empreinte de l'archive). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE packs ADD COLUMN signerKey TEXT")
                db.execSQL("ALTER TABLE packs ADD COLUMN archiveSha256 TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
