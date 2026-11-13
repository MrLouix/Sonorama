package io.github.mrlouix.sonorama.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PackEntity::class,
        SoundEntity::class,
        CardEntity::class,
        CardSoundEntity::class,
        PageEntity::class,
        PageCardEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sonorama.db").build()
    }
}
