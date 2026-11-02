package com.bustramnantes.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bustramnantes.app.data.local.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :favoriteId")
    suspend fun delete(favoriteId: String)

    @Query("SELECT * FROM favorites ORDER BY position ASC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE id = :id")
    suspend fun getById(id: String): FavoriteEntity?

    /** Used by drag-to-reorder to persist a favorite's new sort position. */
    @Query("UPDATE favorites SET position = :position WHERE id = :favoriteId")
    suspend fun updatePosition(favoriteId: String, position: Int)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM favorites")
    suspend fun nextPosition(): Int

    /**
     * Inserts [favorite] at the end of the list, computing its position atomically inside the same
     * transaction as the read - so two concurrent adds can't both read the same max and collide.
     */
    @Transaction
    suspend fun insertAtEnd(favorite: FavoriteEntity) {
        insert(favorite.copy(position = nextPosition()))
    }

    /**
     * Persists a full new ordering in one transaction: either every position update lands or none
     * does (no half-reordered state), and the observers see a single consistent emission.
     */
    @Transaction
    suspend fun reorder(orderedFavoriteIds: List<String>) {
        orderedFavoriteIds.forEachIndexed { index, favoriteId -> updatePosition(favoriteId, index) }
    }

    @Query("DELETE FROM favorites")
    suspend fun clearAll()

    @Query("SELECT * FROM favorites")
    suspend fun getAll(): List<FavoriteEntity>

    @Update
    suspend fun update(favorite: FavoriteEntity)

    @Update
    suspend fun updateAll(favorites: List<FavoriteEntity>)
}
