package com.bustramnantes.app.domain.repository

/** Debug-build diagnostics of the on-device timetable pipeline. */
fun interface TransitDataDiagnostics {
    /**
     * Downloads the current GTFS publication and converts it on this device, installing it when it is
     * newer; returns a human-readable report (download, conversion time, peak heap). Also logged.
     */
    suspend fun convertNow(): String
}
