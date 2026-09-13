package com.bustramnantes.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bustramnantes.app.data.local.dao.FavoriteDao
import com.bustramnantes.app.data.local.entity.FavoriteEntity

/**
 * The app's single Room database: favorites - user data, fully local, no remote
 * sync. Stops and lines are never stored here: they come from the packed GTFS network (see
 * `TransitNetworkRepository`). Every migration is additive; never fall back to a destructive one.
 */
@Database(
    entities = [
        FavoriteEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        const val DATABASE_NAME = "bus_et_tram.db"

        /**
         * v1 → v2: adds `favorites.directionName` so a saved line+direction favorite can show the
         * readable direction name (e.g. "Gare Sud") rather than the raw direction code. Additive,
         * nullable column - existing favorites keep working (their directionName stays null and the
         * card falls back to the code).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN directionName TEXT")
            }
        }
    }
}
