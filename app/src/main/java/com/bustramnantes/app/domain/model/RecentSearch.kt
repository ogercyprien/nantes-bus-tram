package com.bustramnantes.app.domain.model

/** Kind of entry stored by [RecentSearchRepository]. */
enum class RecentSearchType {
    STOP,
    ADDRESS,
}

/**
 * A recently-searched stop or address, capped at 10 entries (Room table `recent_search`).
 * [stopId] is set for [RecentSearchType.STOP] entries; [lat]/[lon]
 * are set for [RecentSearchType.ADDRESS] entries.
 */
data class RecentSearch(
    val id: Long,
    val query: String,
    val type: RecentSearchType,
    val stopId: String?,
    val lat: Double?,
    val lon: Double?,
    val searchedAt: Long,
)
