package com.bustramnantes.transit.gtfs

import com.bustramnantes.transit.TransitNetwork

/**
 * The **bacs de Loire** (Département de Loire-Atlantique, free): the bacs "B1" Basse-Indre ↔ Indret
 * and "B2" Couëron ↔ Le Pellerin, both inside Nantes Métropole and used by the official Naolib planner,
 * but absent from the Naolib GTFS.
 *
 * Source (Licence Ouverte v2): dataset `224400028_horaires-bacs-loire-en-loire-atlantique-gtfs` on
 * data.loire-atlantique.fr; one zip per year (`gtfs_bac_loire2026.zip`), listed by [EXPORTS_URL] - never
 * hard-code the file. 4 stops (no parent stations), 2 routes, `frequencies.txt` (every 20 min,
 * `exact_times=0`), weekday / weekend+holiday calendars to 2027-12-31.
 *
 * Folded into the Naolib feed with [GtfsSource.appending]: landings numbered from
 * [TransitNetwork.EXTERNAL_NUMBER_BASE] + 900,000 (`FR_NAOLIB:StopPlace:9900003` for Basse-Indre), route
 * ids `FR_NAOLIB:Line:B1`/`B2` ("Bac B1" should Naolib ever use B1 itself), long name "Bac de Loire · Basse-Indre ↔ Indret", headsign = the arrival
 * bank.
 */
object BacsDeLoire {
    const val EXPORTS_URL =
        "https://data.loire-atlantique.fr/api/v2/catalog/datasets/224400028_horaires-bacs-loire-en-loire-atlantique-gtfs/alternative_exports"

    val SPEC = AppendedFeedSpec(
        prefix = "BAC44",
        numberBase = TransitNetwork.EXTERNAL_NUMBER_BASE + 900_000,
        color = "00758F",
        textColor = "FFFFFF",
        longName = { "Bac de Loire · " + it.replace("<>", "↔").replace(Regex("\\s+"), " ").trim() },
        headsign = { it.substringAfter('→', it).trim() },
        collisionPrefix = "Bac",
    )

    /**
     * The current zip's URL from the portal's export listing (JSON `alternative_exports[].metas`):
     * the first `.zip` export.
     */
    fun zipUrlFrom(exportsJson: String): String? =
        Regex("\"url\"\\s*:\\s*\"([^\"]+_zip)\"").find(exportsJson)?.groupValues?.get(1)
}
