package com.bustramnantes.app.presentation.settings

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bustramnantes.app.data.local.AppPreferencesDataStore
import com.bustramnantes.app.domain.model.DataResult
import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.domain.repository.TransitDataDiagnostics
import com.bustramnantes.app.domain.repository.LineRepository
import com.bustramnantes.app.domain.repository.StopRepository
import com.bustramnantes.app.presentation.common.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** User intents for the Settings screen - single entry point [SettingsViewModel.onEvent]. */
sealed interface SettingsEvent {
    data class ThemeModeSelected(val mode: ThemeMode) : SettingsEvent

    /** "Effacer" tapped - opens the cache-clear confirmation dialog. */
    data object ClearCacheClicked : SettingsEvent

    /** Confirmation dialog dismissed without confirming. */
    data object ClearCacheDismissed : SettingsEvent

    /** Confirmed in the dialog - clears stop+line caches and re-fetches immediately. */
    data object ClearCacheConfirmed : SettingsEvent

    /** "Tout supprimer" tapped - opens the favorites-clear confirmation dialog. */
    data object ClearFavoritesClicked : SettingsEvent

    /** Confirmation dialog dismissed without confirming. */
    data object ClearFavoritesDismissed : SettingsEvent

    /** Confirmed in the dialog - deletes every favorite. */
    data object ClearFavoritesConfirmed : SettingsEvent

    /** Debug builds: run the on-device GTFS conversion diagnostic. */
    data object DebugConvertGtfsClicked : SettingsEvent
}

/** One-off Settings effects (snackbars) delivered via [SettingsViewModel.effects]. */
sealed interface SettingsEffect {
    /** Debug: result of the on-device GTFS conversion. */
    data class DebugReport(val text: String) : SettingsEffect
    data object CacheCleared : SettingsEffect
    data object CacheClearFailed : SettingsEffect
    data object FavoritesCleared : SettingsEffect
}

/**
 * Settings screen state, exposed by [SettingsViewModel] as a single `StateFlow`.
 */
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You dynamic color only applies on API 31+ (see `BusEtTramTheme`). */
    val dynamicColorAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    val stopsCacheTimestamp: Long? = null,
    val linesCacheTimestamp: Long? = null,
    /** `true` while the "Effacer" clear-then-refetch round trip is in flight. */
    val isClearingCache: Boolean = false,
    val favoritesCount: Int = 0,
    val showClearCacheDialog: Boolean = false,
    val showClearFavoritesDialog: Boolean = false,
    val appVersionName: String = "",
    /** Debug: the on-device GTFS conversion diagnostic is running. */
    val isDebugConverting: Boolean = false,
)

/**
 * MVI ViewModel for the Settings screen.
 *
 * Theme changes are persisted immediately (no explicit "save" action) via
 * [AppPreferencesDataStore.setThemeMode] and observed back so the radio group always reflects the
 * stored value, even if it was somehow changed elsewhere.
 *
 * "Effacer" (cache) clears both [StopRepository] and [LineRepository] caches and re-fetches both
 * immediately, rather than just clearing and waiting for the next natural refresh.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferencesDataStore: AppPreferencesDataStore,
    private val stopRepository: StopRepository,
    private val lineRepository: LineRepository,
    private val favoriteRepository: FavoriteRepository,
    private val transitDataDiagnostics: TransitDataDiagnostics,
) : ViewModel() {

    /** Debug builds only: the "convert the GTFS on this phone" diagnostic is shown. */
    val isDebugBuild: Boolean = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val _uiState = MutableStateFlow(SettingsUiState(appVersionName = readAppVersionName()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<SettingsEffect> = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            appPreferencesDataStore.themeMode.collect { mode ->
                _uiState.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            favoriteRepository.observeFavorites().collect { favorites ->
                _uiState.update { it.copy(favoritesCount = favorites.size) }
            }
        }
        refreshCacheTimestamps()
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.ThemeModeSelected -> selectThemeMode(event.mode)
            SettingsEvent.ClearCacheClicked -> _uiState.update { it.copy(showClearCacheDialog = true) }
            SettingsEvent.ClearCacheDismissed -> _uiState.update { it.copy(showClearCacheDialog = false) }
            SettingsEvent.ClearCacheConfirmed -> clearCache()
            SettingsEvent.ClearFavoritesClicked -> _uiState.update { it.copy(showClearFavoritesDialog = true) }
            SettingsEvent.ClearFavoritesDismissed -> _uiState.update { it.copy(showClearFavoritesDialog = false) }
            SettingsEvent.ClearFavoritesConfirmed -> clearFavorites()
            SettingsEvent.DebugConvertGtfsClicked -> debugConvertGtfs()
        }
    }

    private fun selectThemeMode(mode: ThemeMode) {
        // Optimistic - avoids a visible round trip through DataStore before the radio group updates.
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch { appPreferencesDataStore.setThemeMode(mode) }
    }

    private fun refreshCacheTimestamps() {
        viewModelScope.launch {
            val stopsTimestamp = stopRepository.getStopsCacheTimestamp()
            val linesTimestamp = lineRepository.getLinesCacheTimestamp()
            _uiState.update {
                it.copy(stopsCacheTimestamp = stopsTimestamp, linesCacheTimestamp = linesTimestamp)
            }
        }
    }

    private fun clearCache() {
        _uiState.update { it.copy(showClearCacheDialog = false, isClearingCache = true) }
        viewModelScope.launch {
            stopRepository.clearStopsCache()
            lineRepository.clearLinesCache()
            val stopsResult = stopRepository.refreshStops()
            val linesResult = lineRepository.refreshLines()
            refreshCacheTimestamps()
            _uiState.update { it.copy(isClearingCache = false) }
            val failed = stopsResult is DataResult.Error || linesResult is DataResult.Error
            _effects.emit(if (failed) SettingsEffect.CacheClearFailed else SettingsEffect.CacheCleared)
        }
    }

    private fun debugConvertGtfs() {
        if (_uiState.value.isDebugConverting) return
        _uiState.update { it.copy(isDebugConverting = true) }
        viewModelScope.launch {
            val report = runCatching { transitDataDiagnostics.convertNow() }.getOrElse { "Échec : $it" }
            _uiState.update { it.copy(isDebugConverting = false) }
            _effects.emit(SettingsEffect.DebugReport(report))
        }
    }

    private fun clearFavorites() {
        _uiState.update { it.copy(showClearFavoritesDialog = false) }
        viewModelScope.launch {
            favoriteRepository.clearAllFavorites()
            _effects.emit(SettingsEffect.FavoritesCleared)
        }
    }

    private fun readAppVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
