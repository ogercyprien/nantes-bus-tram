package com.bustramnantes.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only accessor for the bundled Naolib line-raster seed (`assets/seed/[ASSET_NAME]`), an
 * MBTiles file (a SQLite DB of pre-rendered PNG tiles). It lets brand-new / offline users see the
 * colored line traces the instant the map opens, before any tile is fetched from the network - the
 * same offline-first idea as the stops/lines JSON seed, but for map imagery.
 *
 * The visible lines are a raster overlay (`/data/raster/v5/{z}/{x}/{y}.png`); [SeedTileInterceptor]
 * calls [tile] to short-circuit those requests to this bundle when a tile is present. Zoom levels
 * beyond the seeded range (z6-14) simply miss here and fall through to the network, and MapLibre
 * over-zooms the seeded tiles in the meantime so *something* is always drawn.
 *
 * **Why copy-to-disk:** SQLite can only open a real file path, not an `AssetManager` stream, so the
 * asset is copied into `filesDir` once. A [SEED_VERSION] marker file guards the copy: bumping
 * [SEED_VERSION] (whenever the bundled `.mbtiles` is regenerated) forces a one-time re-copy on the
 * next launch, so a stale copy never outlives an app update.
 *
 * **Thread-safety:** [tile] is called concurrently from OkHttp dispatcher threads. The lazy [db]
 * open is synchronized once; `SQLiteDatabase` itself serializes concurrent reads internally. Every
 * failure path (missing/corrupt asset, query error) degrades to `null` → the request falls through
 * to the network, so a bad bundle can never break the map.
 */
@Singleton
class MbTilesTileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val db: SQLiteDatabase? by lazy { openSeedDatabase() }

    /**
     * Returns the raw PNG bytes for slippy tile ([z]/[x]/[y]), or `null` if the tile is not in the
     * bundle (blank area, un-seeded zoom, or any read failure). MBTiles rows are keyed by the **TMS**
     * row (y-axis flipped versus slippy/XYZ), so [y] is converted with `2^z - 1 - y`.
     */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val database = db ?: return null
        val tmsRow = (1 shl z) - 1 - y
        return runCatching {
            database.query(
                "tiles",
                arrayOf("tile_data"),
                "zoom_level = ? AND tile_column = ? AND tile_row = ?",
                arrayOf(z.toString(), x.toString(), tmsRow.toString()),
                null,
                null,
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
        }.getOrNull()
    }

    private fun openSeedDatabase(): SQLiteDatabase? = runCatching {
        val dbFile = File(context.filesDir, ASSET_NAME)
        val marker = File(context.filesDir, "$ASSET_NAME.version")
        val current = marker.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
        if (!dbFile.exists() || current != SEED_VERSION) {
            context.assets.open("seed/$ASSET_NAME").use { input ->
                dbFile.outputStream().use { output -> input.copyTo(output) }
            }
            marker.writeText(SEED_VERSION.toString())
        }
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }.getOrNull()

    companion object {
        private const val ASSET_NAME = "naolib_lines_v5_z6-14.mbtiles"

        /** Bump whenever the bundled `.mbtiles` is regenerated, to force a re-copy after an update. */
        private const val SEED_VERSION = 1
    }
}
