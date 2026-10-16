package com.bustramnantes.app.presentation.disruptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.repository.DisruptionRepository
import com.bustramnantes.app.domain.repository.LineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * The "Perturbations" screen: every notice not over yet, in force first, optionally narrowed to one
 * line. [lineFilters] are the lines some notice concerns, in catalog order.
 */
data class DisruptionsUiState(
    val inForce: List<Disruption> = emptyList(),
    val upcoming: List<Disruption> = emptyList(),
    val lineFilters: List<Line> = emptyList(),
    val selectedLineId: String? = null,
    val linesById: Map<String, Line> = emptyMap(),
    val openDisruption: Disruption? = null,
) {
    val isEmpty: Boolean get() = inForce.isEmpty() && upcoming.isEmpty()
}

sealed interface DisruptionsEvent {
    data class LineFilterSelected(val lineId: String?) : DisruptionsEvent
    data class DisruptionClicked(val disruption: Disruption) : DisruptionsEvent
    data object DismissDisruption : DisruptionsEvent
}

@HiltViewModel
class DisruptionsViewModel @Inject constructor(
    private val disruptionRepository: DisruptionRepository,
    lineRepository: LineRepository,
) : ViewModel() {

    private val selection = MutableStateFlow<Pair<String?, Disruption?>>(null to null)

    val uiState: StateFlow<DisruptionsUiState> = combine(
        disruptionRepository.disruptions,
        lineRepository.observeLines(),
        selection,
    ) { all, lines, (lineId, open) -> build(all, lines, lineId, open, Clock.System.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DisruptionsUiState())

    init {
        viewModelScope.launch { disruptionRepository.refreshIfStale() }
    }

    fun onEvent(event: DisruptionsEvent) {
        selection.value = when (event) {
            is DisruptionsEvent.LineFilterSelected -> event.lineId to null
            is DisruptionsEvent.DisruptionClicked -> selection.value.first to event.disruption
            DisruptionsEvent.DismissDisruption -> selection.value.first to null
        }
    }

    companion object {
        internal fun build(all: List<Disruption>, lines: List<Line>, lineId: String?, open: Disruption?, now: Instant): DisruptionsUiState {
            val live = all.filterNot { it.isOverAt(now) }
            val concerned = live.flatMapTo(HashSet()) { it.lineIds }
            val shown = if (lineId == null) live else live.filter { it.concernsLine(lineId) }
            val (inForce, upcoming) = shown.partition { it.isActiveAt(now) }
            return DisruptionsUiState(
                inForce = inForce.sortedBy { d -> d.periods.mapNotNull { it.end }.minOrNull() ?: Instant.DISTANT_FUTURE },
                upcoming = upcoming.sortedBy { it.nextStartAfter(now) ?: Instant.DISTANT_FUTURE },
                lineFilters = lines.filter { it.id in concerned },
                selectedLineId = lineId?.takeIf { it in concerned },
                linesById = lines.associateBy { it.id },
                openDisruption = open,
            )
        }
    }
}
