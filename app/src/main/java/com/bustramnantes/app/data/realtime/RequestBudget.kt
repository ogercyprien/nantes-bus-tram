package com.bustramnantes.app.data.realtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A "one request per [minIntervalMs]" budget - Okina's keyless plans allow **1 request / 30 s per IP
 * and per gateway API** (SIRI and GTFS-RT have separate budgets; a 429 carries no Retry-After). The
 * last request time is read from / written to [load]/[store] (DataStore for SIRI) so the app process,
 * the widget worker and a restarted process all share one budget. The slot is consumed **before**
 * the request, so a failure (429 included) never leads to an immediate retry.
 */
class RequestBudget(
    private val minIntervalMs: Long,
    private val load: suspend () -> Long,
    private val store: suspend (Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /**
     * Waits for the next slot, then - if [precondition] still holds - consumes it and runs [block]
     * (null otherwise: the slot is left unused). Calls are serialised.
     */
    suspend fun <T> withSlot(precondition: () -> Boolean = { true }, block: suspend () -> T): T? = mutex.withLock {
        // Loop, don't just sleep once: another holder of the same persisted timestamp (a second
        // process) may have taken the slot while we slept.
        while (true) {
            val wait = load() + minIntervalMs - clock()
            if (wait <= 0) break
            delay(wait)
        }
        if (!precondition()) return@withLock null
        store(clock())
        block()
    }

    /** Consumes the slot and runs [block] only if one is free right now; null otherwise. */
    suspend fun <T> tryWithSlot(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        try {
            if (clock() < load() + minIntervalMs) return null
            store(clock())
        } finally {
            mutex.unlock()
        }
        return block()
    }
}
