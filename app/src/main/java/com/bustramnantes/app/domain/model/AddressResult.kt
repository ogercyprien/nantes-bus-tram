package com.bustramnantes.app.domain.model

/**
 * A single address suggestion from the geopf.fr geocoding API.
 */
data class AddressResult(
    val label: String,
    val city: String,
    val lat: Double,
    val lon: Double,
)
