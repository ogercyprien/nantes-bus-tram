package com.bustramnantes.app.presentation.common.utils

import com.bustramnantes.app.domain.model.DataResult
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * User-facing message for a network/connectivity failure. Shown instead of the raw exception text
 * (e.g. `"Unable to resolve host \"api.okina.fr\"…"`), which leaked to the UI before.
 */
const val CONNECTIVITY_ERROR_MESSAGE =
    "Pas de connexion Internet. Impossible de récupérer les informations pour le moment."

/**
 * `true` when [this] (or any wrapped [cause]) is a network-reachability failure - no connection, DNS
 * failure, refused/timed-out connection. Retrofit/OkHttp surface these as [IOException] subtypes, so
 * a plain [IOException] on one of our HTTP calls is treated as connectivity (serialization errors,
 * which are not I/O errors, fall through to the caller's generic fallback message).
 */
fun Throwable?.isConnectivityError(): Boolean = when (this) {
    null -> false
    is UnknownHostException, is ConnectException, is SocketTimeoutException, is IOException -> true
    else -> cause.isConnectivityError()
}

/**
 * Maps a [DataResult.Error] to a friendly, French, user-facing message: the connectivity message
 * when the failure is a network issue, otherwise [fallback]. The raw [Throwable.message] (technical,
 * English) is intentionally never surfaced to the user.
 */
fun DataResult.Error.toUserMessage(fallback: String): String =
    if (throwable.isConnectivityError()) CONNECTIVITY_ERROR_MESSAGE else fallback
