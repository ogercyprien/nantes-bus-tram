package com.bustramnantes.app.domain.usecase

import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.model.ServedLine
import com.bustramnantes.app.domain.model.Stop
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.repository.TimetableRepository
import javax.inject.Inject

/** A stop's lines resolved locally: [lines] for badges, [servedLines] for departure blocks. */
data class CachedStopLines(
    val lines: List<Line>,
    val servedLines: List<ServedLine>,
)

/**
 * Resolves the lines that serve a stop entirely **locally** (no network), from the GTFS network:
 * - [CachedStopLines.lines] - every line through the stop ([Stop.linkedLines]), for the coloured
 *   badges under the stop name, in [Stop.linkedLines] order;
 * - [CachedStopLines.servedLines] - the line+direction blocks Stop Detail renders immediately (each
 *   with a spinner) while the live times load. Terminus-accurate: a direction the stop only ends is
 *   not listed.
 */
class GetCachedStopLinesUseCase @Inject constructor(
    private val lineRepository: LineRepository,
    private val timetableRepository: TimetableRepository,
) {
    suspend operator fun invoke(stop: Stop): CachedStopLines {
        val lines = stop.linkedLines.distinct().mapNotNull { shortName -> lineRepository.getLineByShortName(shortName) }
        return CachedStopLines(lines = lines, servedLines = timetableRepository.getServedLines(stop.id))
    }
}
