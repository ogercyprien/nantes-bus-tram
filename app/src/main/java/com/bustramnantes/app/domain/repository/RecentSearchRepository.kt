package com.bustramnantes.app.domain.repository

import com.bustramnantes.app.domain.model.RecentSearch
import kotlinx.coroutines.flow.Flow

/**
 * Recent stop/address searches, capped at 10 entries with oldest evicted first.
 * The cap is enforced by the implementation, not by callers.
 */
interface RecentSearchRepository {

    /** Most recent first. */
    fun observeRecentSearches(): Flow<List<RecentSearch>>

    suspend fun addRecentSearch(recentSearch: RecentSearch)

    suspend fun deleteRecentSearch(id: Long)

    suspend fun clearRecentSearches()
}
