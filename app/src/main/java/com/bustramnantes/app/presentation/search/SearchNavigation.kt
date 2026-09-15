package com.bustramnantes.app.presentation.search

import com.bustramnantes.app.presentation.common.navigation.Screen

/**
 * NAV_CONTRACT EXTENSION - Search's Adresse mode needs to open the Stop Detail
 * bottom sheet centered on an ad-hoc geocoded coordinate that has no `Stop` row at all, so there
 * is no valid `stopId` to pass.
 *
 * Reconciled during integration: [Screen.StopDetail]'s `ROUTE_PATTERN`/`arguments`/`createRoute`
 * now declare `addressLabel`/`addressLat`/`addressLon` as first-class optional query args
 * (mirroring `lineId`/`direction`), and [com.bustramnantes.app.presentation.stop.StopDetailViewModel]
 * reads them from `SavedStateHandle` to drive its address-only fallback mode. A synthetic `stopId`
 * of the form `"address:<lat>:<lon>"` is still passed alongside them so `StopRepository.getStopById`
 * simply misses (rather than a blank id being ambiguous with a missing arg).
 *
 * See also [SearchViewModel.onAddressResultSelected] / [SearchViewModel.onRecentSearchSelected]
 * for the only two call sites.
 */
internal fun addressStopDetailRoute(label: String, lat: Double, lon: Double): String {
    val syntheticStopId = "address:$lat:$lon"
    return Screen.StopDetail.createRoute(
        stopId = syntheticStopId,
        addressLabel = label,
        addressLat = lat,
        addressLon = lon,
    )
}
