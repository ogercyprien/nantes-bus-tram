package com.bustramnantes.app.data.network

import com.bustramnantes.app.data.remote.opendata.OpenDataApiService
import com.bustramnantes.transit.gtfs.GtfsPublication
import java.time.LocalDate
import javax.inject.Inject

/** The current GTFS publication: its portal file id, download URL and validity window. */
data class GtfsFeed(
    val fileId: String,
    val url: String,
    val validFrom: LocalDate?,
    val validUntil: LocalDate?,
) {
    /**
     * Change key ([GtfsPublication.key]): file id + validity window - the portal republishes under
     * the same file id, so the id alone would never see a new publication.
     */
    val key: String get() = GtfsPublication.key(fileId, validFrom?.toString(), validUntil?.toString())
}

/**
 * Resolves the newest GTFS publication through the open-data records API. Picks the record with the
 * latest `debut_de_validite` (there is one record today; several could coexist around a changeover).
 */
class GtfsFeedResolver @Inject constructor(
    private val api: OpenDataApiService,
) {
    suspend fun latest(): GtfsFeed {
        val record = api.getGtfsRecords().results
            .filter { !it.file?.id.isNullOrBlank() && !it.file?.url.isNullOrBlank() }
            .maxByOrNull { it.validFrom.orEmpty() }
            ?: throw IllegalStateException("no GTFS publication listed")
        return GtfsFeed(
            fileId = record.file!!.id!!,
            url = record.file.url!!,
            validFrom = record.validFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            validUntil = record.validUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }
}
