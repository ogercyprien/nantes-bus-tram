package com.bustramnantes.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.bustramnantes.app.data.local.dao.FavoriteDao
import com.bustramnantes.app.data.local.entity.FavoriteEntity

/**
 * The app's single Room database: favorites - user data, fully local, no remote
 * sync. Stops and lines are never stored here: they come from the packed GTFS network (see
 * `TransitNetworkRepository`).
 */
@Database(
    entities = [
        FavoriteEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        const val DATABASE_NAME = "bus_et_tram.db"
    }
}
