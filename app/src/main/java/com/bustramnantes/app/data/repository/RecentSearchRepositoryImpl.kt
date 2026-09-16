package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.local.dao.RecentSearchDao
import com.bustramnantes.app.data.local.entity.RecentSearchEntity
import com.bustramnantes.app.domain.model.RecentSearch
import com.bustramnantes.app.domain.model.RecentSearchType
import com.bustramnantes.app.domain.repository.RecentSearchRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Thin Room-backed CRUD/Flow wrapper around `recent_search`. Enforces the
 * 10-entry cap by calling [RecentSearchDao.trimToCap] after every insert, per that DAO method's
 * own doc comment.
 */
@Singleton
class RecentSearchRepositoryImpl @Inject constructor(
    private val recentSearchDao: RecentSearchDao,
) : RecentSearchRepository {

    override fun observeRecentSearches(): Flow<List<RecentSearch>> =
        recentSearchDao.observeAll().map { entities -> entities.map(RecentSearchEntity::toDomain) }

    override suspend fun addRecentSearch(recentSearch: RecentSearch) {
        recentSearchDao.insert(recentSearch.toEntity())
        recentSearchDao.trimToCap(MAX_RECENT_SEARCHES)
    }

    override suspend fun deleteRecentSearch(id: Long) = recentSearchDao.deleteById(id)

    override suspend fun clearRecentSearches() = recentSearchDao.clearAll()

    companion object {
        private const val MAX_RECENT_SEARCHES = 10
    }
}

private fun RecentSearchEntity.toDomain(): RecentSearch = RecentSearch(
    id = id,
    query = query,
    type = RecentSearchType.valueOf(type),
    stopId = stopId,
    lat = lat,
    lon = lon,
    searchedAt = searchedAt,
)

private fun RecentSearch.toEntity(): RecentSearchEntity = RecentSearchEntity(
    id = id,
    query = query,
    type = type.name,
    stopId = stopId,
    lat = lat,
    lon = lon,
    searchedAt = searchedAt,
)
