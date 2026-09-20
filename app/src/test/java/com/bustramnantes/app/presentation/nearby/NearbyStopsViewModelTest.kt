package com.bustramnantes.app.presentation.nearby

import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.repository.StopRepository
import com.bustramnantes.app.domain.usecase.nearby.GetNearbyStopsUseCase
import com.bustramnantes.app.presentation.search.location.LocationProvider
import com.bustramnantes.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Behaviour of [NearbyStopsViewModel]'s location strategy, and the regression it guards: the
 * last-known ("resume") reload must only *populate* an empty list. Once a list is shown - e.g. a list
 * the user just forced to their current position - a resume (which also fires when the Stop Detail
 * dialog opened from this tab is dismissed) must NOT reload it from the possibly-stale last-known
 * fix, which would silently revert it to the old location.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyStopsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Two distinct places; getNearbyStops returns a distinct stop per place so a list's origin is
    // identifiable by its single stop id.
    private val oldPlace = 47.20 to -1.55
    private val newPlace = 47.25 to -1.60

    private fun stopAt(place: Pair<Double, Double>): Stop {
        val id = if (place == oldPlace) "OLD" else "NEW"
        return Stop(id = id, name = id, lat = place.first, lon = place.second, linkedLines = emptyList(), accessible = false)
    }

    /** Location source whose last-known and fresh fixes are set independently, mimicking a stale cache. */
    private class FakeLocationProvider(
        var granted: Boolean = true,
        var lastKnown: Pair<Double, Double>? = null,
        var current: Pair<Double, Double>? = null,
    ) : LocationProvider {
        override fun hasLocationPermission() = granted
        override fun getLastKnownLocation() = if (granted) lastKnown else null
        override suspend fun getCurrentLocation() = if (granted) current ?: lastKnown else null
    }

    private class FakeStopRepository(val nearby: (Double, Double) -> List<Stop>) : StopRepository {
        override suspend fun getNearbyStops(lat: Double, lon: Double, limit: Int) = nearby(lat, lon)
        override fun observeStops(): Flow<List<Stop>> = flowOf(emptyList())
        override suspend fun searchStops(query: String, userLat: Double?, userLon: Double?) = emptyList<Stop>()
        override suspend fun getStopById(id: String): Stop? = null
        override suspend fun refreshStops(): DataResult<Unit> = DataResult.Success(Unit)
        override suspend fun clearStopsCache() = Unit
        override suspend fun getStopsCacheTimestamp(): Long? = null
    }

    private class FakeLineRepository : LineRepository {
        override fun observeLines(): Flow<List<Line>> = flowOf(emptyList())
        override suspend fun getLineById(id: String): Line? = null
        override suspend fun getLineByShortName(shortName: String): Line? = null
        override suspend fun refreshLines(): DataResult<Unit> = DataResult.Success(Unit)
        override suspend fun clearLinesCache() = Unit
        override suspend fun getLinesCacheTimestamp(): Long? = null
    }

    private class FakeFavoriteRepository : FavoriteRepository {
        override fun observeFavorites(): Flow<List<Favorite>> = flowOf(emptyList())
        override suspend fun getFavoriteById(id: String): Favorite? = null
        override suspend fun addFavorite(favorite: Favorite) = Unit
        override suspend fun removeFavorite(favoriteId: String) = Unit
        override suspend fun reorderFavorites(orderedFavoriteIds: List<String>) = Unit
        override suspend fun clearAllFavorites() = Unit
    }

    private fun viewModel(location: FakeLocationProvider): NearbyStopsViewModel {
        val useCase = GetNearbyStopsUseCase(
            stopRepository = FakeStopRepository { lat, lon -> listOf(stopAt(lat to lon)) },
            lineRepository = FakeLineRepository(),
            favoriteRepository = FakeFavoriteRepository(),
        )
        return NearbyStopsViewModel(location, useCase)
    }

    private fun NearbyStopsViewModel.stopIds() = uiState.value.stops.map { it.stop.id }

    @Test
    fun `initial resume populates the list from the last-known fix`() = runTest {
        val vm = viewModel(FakeLocationProvider(lastKnown = oldPlace, current = newPlace))

        vm.refresh()

        assertEquals(listOf("OLD"), vm.stopIds())
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.hasLocationPermission)
    }

    @Test
    fun `a resume does not revert a freshly-refreshed list to the stale last-known location`() = runTest {
        // Cache stays pointed at the old place even after the user moves (the real-world stale-fix case).
        val location = FakeLocationProvider(lastKnown = oldPlace, current = newPlace)
        val vm = viewModel(location)

        vm.refresh() // initial populate from last-known -> OLD
        assertEquals(listOf("OLD"), vm.stopIds())

        vm.refreshFresh() // user forces current fix -> NEW
        assertEquals(listOf("NEW"), vm.stopIds())

        // Resume fires again (e.g. the Stop Detail dialog is dismissed). Must keep NEW, not revert.
        vm.refresh()
        assertEquals("resume must not clobber the refreshed list", listOf("NEW"), vm.stopIds())
    }

    @Test
    fun `manual fresh refresh updates the list even when one is already shown`() = runTest {
        val location = FakeLocationProvider(lastKnown = oldPlace, current = newPlace)
        val vm = viewModel(location)

        vm.refresh()
        assertEquals(listOf("OLD"), vm.stopIds())

        vm.refreshFresh()

        assertEquals(listOf("NEW"), vm.stopIds())
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `denied permission clears the list and flags`() = runTest {
        val vm = viewModel(FakeLocationProvider(granted = true, lastKnown = oldPlace))
        vm.refresh()
        assertEquals(listOf("OLD"), vm.stopIds())

        vm.onLocationPermissionResult(granted = false)

        assertFalse(vm.uiState.value.hasLocationPermission)
        assertTrue(vm.uiState.value.stops.isEmpty())
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `no fix available surfaces locationUnavailable, and a later resume retries`() = runTest {
        val location = FakeLocationProvider(lastKnown = null, current = null)
        val vm = viewModel(location)

        vm.refresh()
        assertTrue(vm.uiState.value.locationUnavailable)
        assertTrue(vm.uiState.value.stops.isEmpty())

        // The gate only protects a *non-empty* list, so a subsequent resume must still retry - and
        // now that a fix exists it should populate.
        location.lastKnown = oldPlace
        vm.refresh()

        assertFalse(vm.uiState.value.locationUnavailable)
        assertEquals(listOf("OLD"), vm.stopIds())
    }
}
