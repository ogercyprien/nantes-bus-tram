package com.bustramnantes.app.presentation.home

import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.DepartureSource
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.FavoriteType
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.domain.model.StopBoard
import com.bustramnantes.app.domain.repository.DisruptionRepository
import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.repository.RealtimeBoardRepository
import com.bustramnantes.app.domain.repository.RealtimePriority
import com.bustramnantes.app.domain.repository.StopRepository
import com.bustramnantes.app.domain.usecase.home.GetFavoritesWithDeparturesUseCase
import com.bustramnantes.app.domain.usecase.home.RemoveFavoriteUseCase
import com.bustramnantes.app.domain.usecase.home.ReorderFavoritesUseCase
import com.bustramnantes.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pull-to-refresh on Home. Regression: boards come from the real-time cache instantly, so the
 * spinner went on and off within one frame; Material 3's PullToRefreshBox never saw it "refreshing"
 * and stayed stuck at its threshold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val favorite = Favorite("f1", FavoriteType.STOP, "FR_NAOLIB:StopPlace:1", "Commerce", null, null, null, null, null, null, 0, 0)

    private class InstantBoards : RealtimeBoardRepository {
        val updatesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        override val updates: Flow<Unit> = updatesFlow
        override suspend fun boards(stopIds: Collection<String>, priority: RealtimePriority) =
            stopIds.associateWith { StopBoard(emptyList(), provisional = false, source = DepartureSource.SIRI) }
        override suspend fun awaitRealtime(stopIds: Collection<String>, priority: RealtimePriority, timeoutMs: Long) =
            boards(stopIds, priority)
    }

    private val favoriteRepository = object : FavoriteRepository {
        override fun observeFavorites(): Flow<List<Favorite>> = flowOf(listOf(favorite))
        override suspend fun getFavoriteById(id: String): Favorite? = favorite
        override suspend fun addFavorite(favorite: Favorite) = Unit
        override suspend fun removeFavorite(favoriteId: String) = Unit
        override suspend fun reorderFavorites(orderedFavoriteIds: List<String>) = Unit
        override suspend fun clearAllFavorites() = Unit
    }

    private val lineRepository = object : LineRepository {
        override fun observeLines(): Flow<List<Line>> = flowOf(emptyList())
        override suspend fun getLineById(id: String): Line? = null
        override suspend fun getLineByShortName(shortName: String): Line? = null
        override suspend fun refreshLines(): DataResult<Unit> = DataResult.Success(Unit)
        override suspend fun clearLinesCache() = Unit
        override suspend fun getLinesCacheTimestamp(): Long? = null
    }

    private val stopRepository = object : StopRepository {
        override fun observeStops(): Flow<List<Stop>> = emptyFlow()
        override suspend fun searchStops(query: String, userLat: Double?, userLon: Double?): List<Stop> = emptyList()
        override suspend fun getStopById(id: String): Stop? = null
        override suspend fun getNearbyStops(lat: Double, lon: Double, limit: Int): List<Stop> = emptyList()
        override suspend fun refreshStops(): DataResult<Unit> = DataResult.Success(Unit)
        override suspend fun clearStopsCache() = Unit
        override suspend fun getStopsCacheTimestamp(): Long? = null
    }

    private val disruptionRepository = object : DisruptionRepository {
        override val disruptions: StateFlow<List<Disruption>> = MutableStateFlow(emptyList())
        override suspend fun refreshIfStale() = Unit
    }

    private fun viewModel(boards: InstantBoards) = HomeViewModel(
        favoriteRepository,
        GetFavoritesWithDeparturesUseCase(boards, lineRepository),
        RemoveFavoriteUseCase(favoriteRepository),
        ReorderFavoritesUseCase(favoriteRepository),
        disruptionRepository,
        stopRepository,
    )

    @Test
    fun `pull-to-refresh shows the spinner, then clears it, even when boards are instant`() = runTest(dispatcher) {
        val vm = viewModel(InstantBoards())
        val seen = mutableListOf<Boolean>()
        val collector = launch { vm.uiState.collect { (it as? HomeUiState.Content)?.let { c -> seen += c.isRefreshing } } }

        vm.onEvent(HomeEvent.RefreshRequested)
        runCurrent()
        assertTrue((vm.uiState.value as HomeUiState.Content).isRefreshing)

        advanceTimeBy(700)
        assertEquals(false, (vm.uiState.value as HomeUiState.Content).isRefreshing)
        // The UI observed it start and stop - what PullToRefreshBox needs to animate and hide.
        val firstOn = seen.indexOf(true)
        assertTrue(firstOn >= 0 && seen.subList(firstOn, seen.size).contains(false))
        collector.cancel()
    }

    @Test
    fun `a background refresh does not cancel or clear a pull-to-refresh in progress`() = runTest(dispatcher) {
        val boards = InstantBoards()
        val vm = viewModel(boards)
        vm.onEvent(HomeEvent.ScreenResumed)
        vm.onEvent(HomeEvent.RefreshRequested)
        runCurrent()
        boards.updatesFlow.tryEmit(Unit) // a real-time fetch lands -> the poller re-asks
        runCurrent()
        assertTrue((vm.uiState.value as HomeUiState.Content).isRefreshing)
        advanceTimeBy(700)
        assertEquals(false, (vm.uiState.value as HomeUiState.Content).isRefreshing)
        vm.onEvent(HomeEvent.ScreenPaused)
    }
}
