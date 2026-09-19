package com.bustramnantes.app.data.remote.dto.geocoding

import kotlinx.serialization.Serializable

/**
 * Response body of `GET https://data.geopf.fr/geocodage/search/` - the French
 * government Géoplateforme / BAN (Base Adresse Nationale) geocoder. This is a well-established
 * public API (formerly `api-adresse.data.gouv.fr`); shape below is the documented standard
 * GeoJSON `FeatureCollection` response, not a guess.
 */
@Serializable
data class GeocodingResponseDto(
    val type: String = "FeatureCollection",
    val features: List<GeocodingFeatureDto> = emptyList(),
    val attribution: String? = null,
    val licence: String? = null,
    val query: String? = null,
    val limit: Int? = null,
)

@Serializable
data class GeocodingFeatureDto(
    val type: String = "Feature",
    val geometry: GeocodingGeometryDto,
    val properties: GeocodingPropertiesDto,
)

/** Standard GeoJSON `Point` geometry: `coordinates` is `[lon, lat]`. */
@Serializable
data class GeocodingGeometryDto(
    val type: String = "Point",
    val coordinates: List<Double>,
)

/** BAN feature properties - see https://adresse.data.gouv.fr/api-doc/adresse. */
@Serializable
data class GeocodingPropertiesDto(
    val label: String,
    val score: Double? = null,
    val id: String? = null,
    val name: String? = null,
    val housenumber: String? = null,
    val street: String? = null,
    val postcode: String? = null,
    val citycode: String? = null,
    val city: String? = null,
    val context: String? = null,
    /** "housenumber" | "street" | "locality" | "municipality". */
    val type: String? = null,
    val importance: Double? = null,
)
