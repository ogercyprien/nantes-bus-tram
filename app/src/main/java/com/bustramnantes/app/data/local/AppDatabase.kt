package com.bustramnantes.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bustramnantes.app.data.local.dao.FavoriteDao
import com.bustramnantes.app.data.local.dao.RecentSearchDao
import com.bustramnantes.app.data.local.entity.FavoriteEntity
import com.bustramnantes.app.data.local.entity.RecentSearchEntity

/**
 * The app's single Room database: favorites and recent searches - user data, fully local, no remote
 * sync. Stops and lines are never stored here: they come from the packed GTFS network (see
 * `TransitNetworkRepository`). Every migration is additive; never fall back to a destructive one.
 */
@Database(
    entities = [
        FavoriteEntity::class,
        RecentSearchEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun recentSearchDao(): RecentSearchDao

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

        /**
         * v2 → v3: adds the `recent_search` table (the last stops searched, shown under the empty
         * search field). New table, nothing to carry over.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recent_search` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `query` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, `stopId` TEXT, `searchedAt` INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v3 → v4: adds `favorites.lineTextColor` so a line favorite's badge uses the line's own text
         * colour (`route_text_color`) rather than a contrast guess. Nullable TEXT, no SQL default
         * (same additive pattern as `favorites.directionName`); existing favorites get NULL and the
         * badge falls back to the computed contrast colour.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN lineTextColor TEXT")
            }
        }

        /**
         * v4 → v5: adds `recent_search.lat` / `lon`, so an address (Géoplateforme) can be a recent
         * search too, not only a stop. Nullable REAL columns; existing stop entries keep NULL.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recent_search ADD COLUMN lat REAL")
                db.execSQL("ALTER TABLE recent_search ADD COLUMN lon REAL")
            }
        }

        /**
         * v5 → v6: adds `favorites.legacyStopId` (nullable), the StopPlace id a favorite was saved
         * with when a newer GTFS publication retired it. The new id can't be worked out in SQL (it
         * needs the network): `FavoriteRekeyer` does that at startup and fills this column.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN legacyStopId TEXT")
            }
        }
    }
}
