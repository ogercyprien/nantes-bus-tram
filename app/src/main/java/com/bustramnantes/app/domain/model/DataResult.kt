package com.bustramnantes.app.domain.model

/**
 * Generic result wrapper used across every repository interface instead of throwing exceptions
 * across the domain/presentation boundary. Kept intentionally minimal (no `Loading` variant):
 * "in flight" is a presentation-layer (ViewModel/UiState) concern, not a data-layer one - a
 * suspend function is either running (caller is suspended) or has produced a [Success]/[Error].
 */
sealed class DataResult<out T> {
    data class Success<out T>(val data: T) : DataResult<T>()
    data class Error(val throwable: Throwable, val message: String? = null) : DataResult<Nothing>()
}

/** Convenience accessor returning the success value, or `null` if this is an [DataResult.Error]. */
fun <T> DataResult<T>.getOrNull(): T? = (this as? DataResult.Success)?.data
