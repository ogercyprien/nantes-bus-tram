package com.bustramnantes.app.presentation.stop

import androidx.lifecycle.SavedStateHandle
import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.model.Direction
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.FavoriteType
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.domain.repository.DisruptionRepository
import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.repository.RealtimeBoardRepository
import com.bustramnantes.app.domain.repository.RealtimePriority
import com.bustramnantes.app.domain.repository.StopRepository
import com.bustramnantes.app.domain.repository.TimetableRepository
import com.bustramnantes.app.domain.usecase.AddFavoriteUseCase
import com.bustramnantes.app.domain.usecase.GetCachedStopLinesUseCase
import com.bustramnantes.app.domain.usecase.GetStopTimetableUseCase
import com.bustramnantes.app.domain.usecase.UpdateFavoriteUseCase
import com.bustramnantes.app.domain.usecase.home.RemoveFavoriteUseCase
import com.bustramnantes.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The star's dialogs: no favorite -> straight to "Ajouter"; otherwise "Favoris à …" with Modifier
 * (rewrites in place: same id and Home position) / Retirer (undoable, back at its position) / Ajouter;
 * Home's "Modifier" lands straight on the editor. Plus the picker's starting choice and duplicates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopDetailFavoritesTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val stopId = "FR_NAOLIB:StopPlace:1"

    private fun fav(id: String, type: FavoriteType, line: String? = null, dir: String? = null, position: Int = 0) =
        Favorite(
            id = id, type = type, stopId = stopId, stopName = "Commerce",
            lineId = line, lineShortName = line?.substringAfterLast(':'), lineColor = line?.let { "00A3E0" },
            lineTextColor = line?.let { "FFFFFF" }, direction = dir, directionName = dir?.let { "Dir $it" },
            position = position, addedAt = 42,
        )

    private class InMemoryFavorites(initial: List<Favorite>) : FavoriteRepository {
        val state = MutableStateFlow(initial)
        override fun observeFavorites(): Flow<List<Favorite>> = state
        override suspend fun getFavoriteById(id: String) = state.value.find { it.id == id }
        override suspend fun addFavorite(favorite: Favorite) =
            state.update { it + favorite.copy(position = (it.maxOfOrNull(Favorite::position) ?: -1) + 1) }
        override suspend fun updateFavorite(favorite: Favorite) =
            state.update { list -> list.map { if (it.id == favorite.id) favorite else it } }
        override suspend fun restoreFavorite(favorite: Favorite) =
            state.update { (it + favorite).sortedBy(Favorite::position) }
        override suspend fun removeFavorite(favoriteId: String) = state.update { list -> list.filter { it.id != favoriteId } }
        override suspend fun reorderFavorites(orderedFavoriteIds: List<String>) = Unit
        override suspend fun clearAllFavorites() = state.update { emptyList() }
    }

    private val lineRepository = object : LineRepository {
        override fun observeLines(): Flow<List<Line>> = flowOf(emptyList())
        override suspend fun getLineById(id: String): Line? = null
        override suspend fun getLineByShortName(shortName: String): Line? = null
        override suspend fun refreshLines(): DataResult<Unit> = DataResult.Success(Unit)
        override suspend fun clearLinesCache() = Unit
        override suspend fun getLinesCacheTimestamp(): Long? = null
    }

    // An unresolvable stop id: no polling, and favorites are matched on the id itself - enough here.
    private val stopRepository = object : StopRepository {
        override fun observeStops(): Flow<List<Stop>> = emptyFlow()
        override suspend fun searchStops(query: String, userLat: Double?, userLon: Double?): List<Stop> = emptyList()
        override suspend fun getStopById(id: String): Stop? = null
        override suspend fun getNearbyStops(lat: Double, lon: Double, limit: Int): List<Stop> = emptyList()
        override suspend fun refreshStops(): DataResult<Unit> = DataResult.Success(Unit)
        override suspend fun clearStopsCache() = Unit
        override suspend fun getStopsCacheTimestamp(): Long? = null
    }

    private val boards = object : RealtimeBoardRepository {
        override val updates: Flow<Unit> = emptyFlow()
        override suspend fun boards(stopIds: Collection<String>, priority: RealtimePriority) = emptyMap<String, Nothing>()
        override suspend fun awaitRealtime(stopIds: Collection<String>, priority: RealtimePriority, timeoutMs: Long) =
            emptyMap<String, Nothing>()
    }

    private val disruptions = object : DisruptionRepository {
        override val disruptions: StateFlow<List<Disruption>> = MutableStateFlow(emptyList())
        override suspend fun refreshIfStale() = Unit
    }

    private fun viewModel(repo: InMemoryFavorites, editFavoriteId: String? = null) = StopDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("stopId" to stopId, "editFavoriteId" to editFavoriteId)),
        stopRepository = stopRepository,
        favoriteRepository = repo,
        getStopTimetableUseCase = GetStopTimetableUseCase(boards),
        getCachedStopLinesUseCase = GetCachedStopLinesUseCase(
            lineRepository,
            object : TimetableRepository { override suspend fun getServedLines(stopId: String) = emptyList<Nothing>() },
        ),
        addFavoriteUseCase = AddFavoriteUseCase(repo),
        updateFavoriteUseCase = UpdateFavoriteUseCase(repo),
        removeFavoriteUseCase = RemoveFavoriteUseCase(repo),
        disruptionRepository = disruptions,
        lineRepository = lineRepository,
    )

    private fun chosen(type: FavoriteType, line: String? = null, dir: String? = null) =
        StopDetailEvent.FavoriteGranularityChosen(type, line, line?.substringAfterLast(':'), "00A3E0", "FFFFFF", dir, dir?.let { "Dir $it" })

    @Test
    fun `without a favorite the star opens the add picker directly`() {
        val vm = viewModel(InMemoryFavorites(emptyList()))
        vm.onEvent(StopDetailEvent.FavoriteActionClicked)
        assertEquals(FavoriteDialog.Add(fromManage = false), vm.uiState.value.favoriteDialog)
        vm.onEvent(StopDetailEvent.DismissFavoriteDialog)
        assertNull(vm.uiState.value.favoriteDialog)
    }

    @Test
    fun `with favorites the star opens the list, and cancelling add or edit goes back to it`() {
        val vm = viewModel(InMemoryFavorites(listOf(fav("a", FavoriteType.STOP))))
        vm.onEvent(StopDetailEvent.FavoriteActionClicked)
        assertEquals(FavoriteDialog.Manage, vm.uiState.value.favoriteDialog)
        vm.onEvent(StopDetailEvent.AddFavoriteClicked)
        vm.onEvent(StopDetailEvent.DismissFavoriteDialog)
        assertEquals(FavoriteDialog.Manage, vm.uiState.value.favoriteDialog)
        vm.onEvent(StopDetailEvent.EditFavoriteClicked("a"))
        assertEquals(FavoriteDialog.Edit("a", fromManage = true), vm.uiState.value.favoriteDialog)
        vm.onEvent(StopDetailEvent.DismissFavoriteDialog)
        assertEquals(FavoriteDialog.Manage, vm.uiState.value.favoriteDialog)
    }

    @Test
    fun `adding from the list adds a second favorite instead of toggling the first`() {
        val repo = InMemoryFavorites(listOf(fav("a", FavoriteType.STOP_LINE_DIRECTION, "FR_NAOLIB:Line:C6", "0")))
        val vm = viewModel(repo)
        vm.onEvent(StopDetailEvent.FavoriteActionClicked)
        vm.onEvent(StopDetailEvent.AddFavoriteClicked)
        vm.onEvent(chosen(FavoriteType.STOP))
        assertEquals(2, repo.state.value.size)
        assertNull(vm.uiState.value.favoriteDialog)
    }

    @Test
    fun `editing rewrites the favorite in place, keeping its id and position`() {
        val repo = InMemoryFavorites(
            listOf(fav("x", FavoriteType.STOP, position = 0), fav("a", FavoriteType.STOP_LINE_DIRECTION, "FR_NAOLIB:Line:C6", "0", position = 1)),
        )
        val vm = viewModel(repo)
        vm.onEvent(StopDetailEvent.FavoriteActionClicked)
        vm.onEvent(StopDetailEvent.EditFavoriteClicked("a"))
        vm.onEvent(chosen(FavoriteType.STOP_LINE, "FR_NAOLIB:Line:C6", "0"))

        val edited = repo.state.value.single { it.id == "a" }
        assertEquals(FavoriteType.STOP_LINE, edited.type)
        assertEquals("FR_NAOLIB:Line:C6", edited.lineId)
        assertNull(edited.direction) // whole line: the direction is cleared
        assertNull(edited.directionName)
        assertEquals(1, edited.position)
        assertEquals(42L, edited.addedAt)
        assertEquals(2, repo.state.value.size)
    }

    @Test
    fun `removing is undoable and the favorite comes back at its position`() = runTest(dispatcher) {
        val a = fav("a", FavoriteType.STOP, position = 0)
        val b = fav("b", FavoriteType.STOP_LINE, "FR_NAOLIB:Line:C6", position = 1)
        val repo = InMemoryFavorites(listOf(a, b))
        val vm = viewModel(repo)
        val effects = mutableListOf<StopDetailEffect>()
        val collector = launch { vm.effects.collect { effects += it } }

        vm.onEvent(StopDetailEvent.FavoriteActionClicked)
        vm.onEvent(StopDetailEvent.RemoveFavoriteClicked(a))
        assertEquals(listOf("b"), repo.state.value.map { it.id })
        assertEquals(FavoriteDialog.Manage, vm.uiState.value.favoriteDialog) // the list stays open

        val message = effects.filterIsInstance<StopDetailEffect.ShowMessage>().single()
        assertEquals("Annuler", message.actionLabel)
        vm.onEvent(message.action!!)
        assertEquals(listOf("a", "b"), repo.state.value.map { it.id })
        collector.cancel()
    }

    @Test
    fun `removing the last favorite closes the list`() {
        val a = fav("a", FavoriteType.STOP)
        val vm = viewModel(InMemoryFavorites(listOf(a)))
        vm.onEvent(StopDetailEvent.FavoriteActionClicked)
        vm.onEvent(StopDetailEvent.RemoveFavoriteClicked(a))
        assertNull(vm.uiState.value.favoriteDialog)
    }

    @Test
    fun `removing from the editor goes back to the list`() {
        val a = fav("a", FavoriteType.STOP)
        val vm = viewModel(InMemoryFavorites(listOf(a, fav("b", FavoriteType.STOP_LINE, "FR_NAOLIB:Line:C6"))))
        vm.onEvent(StopDetailEvent.FavoriteActionClicked)
        vm.onEvent(StopDetailEvent.EditFavoriteClicked("a"))
        vm.onEvent(StopDetailEvent.RemoveFavoriteClicked(a))
        assertEquals(FavoriteDialog.Manage, vm.uiState.value.favoriteDialog)
    }

    @Test
    fun `Home's Modifier opens straight on the editor, and cancelling closes it`() {
        val vm = viewModel(InMemoryFavorites(listOf(fav("a", FavoriteType.STOP))), editFavoriteId = "a")
        assertEquals(FavoriteDialog.Edit("a", fromManage = false), vm.uiState.value.favoriteDialog)
        vm.onEvent(StopDetailEvent.DismissFavoriteDialog)
        assertNull(vm.uiState.value.favoriteDialog)
    }

    @Test
    fun `an editor whose favorite is gone closes`() {
        val vm = viewModel(InMemoryFavorites(listOf(fav("a", FavoriteType.STOP))), editFavoriteId = "deleted")
        assertNull(vm.uiState.value.favoriteDialog)
    }

    // --- picker logic -------------------------------------------------------------------------

    private val c6 = FavoriteLineChoice(
        "FR_NAOLIB:Line:C6", "C6", "#00A3E0", "#FFFFFF", TransportMode.BUS,
        listOf(Direction("0", "Hermeland"), Direction("1", "Chantrerie")),
    )
    private val tram1 = FavoriteLineChoice(
        "FR_NAOLIB:Line:1", "1", "#007A45", "#FFFFFF", TransportMode.TRAM,
        listOf(Direction("0", "François Mitterrand"), Direction("1", "Ranzay")),
    )

    @Test
    fun `the add picker opens on the whole stop when it is free, with the focused line ready`() {
        val pick = initialAddSelection(listOf(c6, tram1), emptyList(), focusedLineId = tram1.lineId, focusedDirection = "1")
        assertEquals(FavoriteSelection(FavoriteType.STOP, tram1.lineId, "0"), pick)
    }

    @Test
    fun `the add picker skips configurations already saved`() {
        val saved = listOf(
            fav("a", FavoriteType.STOP),
            fav("b", FavoriteType.STOP_LINE, c6.lineId),
            fav("c", FavoriteType.STOP_LINE_DIRECTION, c6.lineId, "0"),
        )
        assertEquals(
            FavoriteSelection(FavoriteType.STOP_LINE_DIRECTION, c6.lineId, "1"),
            initialAddSelection(listOf(c6, tram1), saved, focusedLineId = null, focusedDirection = null),
        )
    }

    @Test
    fun `only an exact twin is a duplicate, and the edited favorite is not its own`() {
        val saved = listOf(fav("a", FavoriteType.STOP), fav("b", FavoriteType.STOP_LINE_DIRECTION, c6.lineId, "0"))
        // A whole-line C6 favorite overlaps "b" but is not a duplicate.
        assertNull(saved.duplicateOf(FavoriteSelection(FavoriteType.STOP_LINE, c6.lineId, "0")))
        assertEquals("b", saved.duplicateOf(FavoriteSelection(FavoriteType.STOP_LINE_DIRECTION, c6.lineId, "0"))?.id)
        assertEquals("a", saved.duplicateOf(FavoriteSelection(FavoriteType.STOP, c6.lineId, "1"))?.id)
        assertNull(saved.duplicateOf(FavoriteSelection(FavoriteType.STOP, null, null), exceptId = "a"))
    }
}
