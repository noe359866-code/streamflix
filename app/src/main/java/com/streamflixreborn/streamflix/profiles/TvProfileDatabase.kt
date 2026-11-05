package com.streamflixreborn.streamflix.profiles

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TvUserProfileEntity::class,
        TvProfileWatchHistoryEntity::class,
        TvProfileFavoriteEntity::class,
        TvCustomAddonEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class TvProfileDatabase : RoomDatabase() {

    abstract fun profileDao(): TvProfileDao

    companion object {
        @Volatile
        private var instance: TvProfileDatabase? = null

        fun getInstance(context: Context): TvProfileDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TvProfileDatabase::class.java,
                    "streamflix_tv_profiles.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
