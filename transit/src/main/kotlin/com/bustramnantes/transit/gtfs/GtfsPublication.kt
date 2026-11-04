package com.bustramnantes.transit.gtfs

/**
 * Identity of a Naolib GTFS publication on data.nantesmetropole.fr: the change key a downloaded
 * network is stored under (`TransitNetwork.feedId`, `filesDir/network/<key>.bin`) and compared with
 * to decide whether a refresh is needed.
 *
 * The portal's file id alone is **not** a change key: the 2026-11-04 publication (valid
 * 2026-11-04..2027-02-05, 20 MB) replaced the 2026-10-11 one (valid 2026-10-09..2027-01-09, 26 MB)
 * under the same id `0cc0469a72de54ee045cb66d1a21de9e`. The download has no ETag, Last-Modified or
 * Content-Length and the record no checksum, so the key adds the record's validity window
 * (`debut_de_validite`, `fin_de_validite`), which every new publication moves. A republication with
 * the same window would go unnoticed; the dataset's `data_processed` timestamp would catch it, but a
 * metadata-only reprocessing would then send every phone a 20 MB download.
 */
object GtfsPublication {
    /** `<fileId>_<validFrom>_<validUntil>`, a missing date as `-` (file-name safe). */
    fun key(fileId: String, validFrom: String?, validUntil: String?): String =
        listOf(fileId, validFrom?.takeIf { it.isNotBlank() } ?: "-", validUntil?.takeIf { it.isNotBlank() } ?: "-")
            .joinToString("_")
}
