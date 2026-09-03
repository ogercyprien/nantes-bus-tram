package com.bustramnantes.app.domain.model

/**
 * Vehicle mode for a [Line] / [Departure], from GTFS `route_type` (0 tram, 3 bus - busways
 * included -, 4 boat). [FERRY] is the Navibus boats (N1/N2/N3, Naolib); [BAC] the bacs de Loire
 * (B1/B2, Département de Loire-Atlantique, appended to the network). Both get the boat-hull badge.
 */
enum class TransportMode {
    BUS,
    TRAM,
    FERRY,
    BAC,
}
