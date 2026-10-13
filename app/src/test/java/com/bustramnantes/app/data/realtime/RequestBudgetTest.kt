package com.bustramnantes.app.data.realtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestBudgetTest {

    @Test
    fun `slots are spaced by the interval and consumed before the call`() = runTest {
        var last = 0L
        val clock = { 1_000_000L + testScheduler.currentTime }
        val budget = RequestBudget(31_000, { last }, { last = it }, clock)
        val starts = mutableListOf<Long>()
        repeat(3) { launch { budget.withSlot { starts += clock() } } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1_000_000L, 1_031_000L, 1_062_000L), starts)
        // A failing call still consumed its slot.
        runCatching { budget.withSlot { throw IllegalStateException("429") } }
        assertEquals(1_093_000L, last)
    }

    @Test
    fun `precondition false leaves the slot unused, tryWithSlot never waits`() = runTest {
        var last = 0L
        val clock = { 1_000_000L + testScheduler.currentTime }
        val budget = RequestBudget(31_000, { last }, { last = it }, clock)
        assertNull(budget.withSlot(precondition = { false }) { 1 })
        assertEquals(0L, last)
        assertEquals(1, budget.tryWithSlot { 1 })
        assertNull(budget.tryWithSlot { 2 })
    }
}
