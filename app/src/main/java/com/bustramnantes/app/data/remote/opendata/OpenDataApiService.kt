package com.bustramnantes.app.data.remote.opendata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Nantes Métropole open-data portal (`data.nantesmetropole.fr`, Opendatasoft explore API v2.1) -
 * published, keyless, Licence Ouverte v2.
 */
interface OpenDataApiService {

    /**
     * The GTFS dataset's records: one per publication, each with the zip's **file id** (the change key;
     * the download URL embeds it and changes on every publication - never hard-code it) and its
     * validity window. In practice a single record.
     */
    @GET("api/explore/v2.1/catalog/datasets/244400404_transports_commun_naolib_nantes_metropole_gtfs/records?limit=20")
    suspend fun getGtfsRecords(): GtfsRecordsDto

    /** Streams a file (the ~26 MB GTFS zip) - never buffered in memory. */
    @Streaming
    @GET
    suspend fun download(@Url url: String): ResponseBody
}

@Serializable
data class GtfsRecordsDto(val results: List<GtfsRecordDto> = emptyList())

@Serializable
data class GtfsRecordDto(
    @SerialName("debut_de_validite") val validFrom: String? = null,
    @SerialName("fin_de_validite") val validUntil: String? = null,
    @SerialName("fichier") val file: GtfsFileDto? = null,
)

@Serializable
data class GtfsFileDto(
    val id: String? = null,
    val url: String? = null,
    val filename: String? = null,
)
