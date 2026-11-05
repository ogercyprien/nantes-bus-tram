package com.bustramnantes.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bustramnantes.app.presentation.common.theme.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Preferences DataStore-backed persistence for app-level settings.
 *
 * Deliberately uses Preferences DataStore rather than Proto DataStore - a single theme enum plus
 * a last-active-tab route string doesn't warrant a `.proto` schema (see foundation-work deviation
 * notes). The underlying `DataStore<Preferences>` singleton is provided by
 * `di/DataStoreModule.kt`.
 *
 * @see ThemeMode Settings > Thème.
 * @see lastActiveTab App-level "restore last active tab" behavior - consumed by a
 * later integration agent from `MainActivity`/the shared `AppNavGraph`, not from this feature.
 */
@Singleton
class AppPreferencesDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** Persisted theme preference, defaulting to [ThemeMode.SYSTEM] until the user picks one. */
    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY]?.let { stored ->
            runCatching { ThemeMode.valueOf(stored) }.getOrNull()
        } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode.name }
    }

    /**
     * Route of the bottom-nav tab ("home" | "search" | "map") last active when the app was
     * backgrounded/closed. Defaults to [DEFAULT_LAST_ACTIVE_TAB] (`"search"`, matching
     * `Screen.Search.BASE_ROUTE` in `presentation.common.navigation` - not imported here to keep
     * this data-layer class decoupled from the presentation layer), the "Recherche
     * on first launch" default before any tab has ever been recorded.
     */
    val lastActiveTab: Flow<String> = dataStore.data.map { prefs ->
        prefs[LAST_ACTIVE_TAB_KEY] ?: DEFAULT_LAST_ACTIVE_TAB
    }

    /**
     * `true` once [setLastActiveTab] has ever been called - i.e. this is *not* the very first
     * app launch. Distinguishes "never recorded" from "explicitly recorded as the default value",
     * which [lastActiveTab] alone cannot: the
     * Recherche-by-default/Accueil-once-a-favorite-exists rule only applies on a genuine first launch, while
     * every subsequent launch always restores [lastActiveTab] verbatim regardless of favorites.
     */
    val hasStoredLastActiveTab: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs.contains(LAST_ACTIVE_TAB_KEY)
    }

    suspend fun setLastActiveTab(route: String) {
        dataStore.edit { prefs -> prefs[LAST_ACTIVE_TAB_KEY] = route }
    }

    /**
     * `true` once the one-time first-launch "welcome / location" dialog has been shown and the user
     * has made their permission choice. Gates that dialog so the explanation + permission prompt
     * appear exactly once per install (see `MainActivity`), never again regardless of the choice.
     */
    val hasSeenLocationWelcome: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[LOCATION_WELCOME_SEEN_KEY] ?: false
    }

    suspend fun setLocationWelcomeSeen() {
        dataStore.edit { prefs -> prefs[LOCATION_WELCOME_SEEN_KEY] = true }
    }

    /**
     * Epoch ms of the last Okina SIRI request (the keyless budget is 1 request / 30 s per IP). Shared
     * by the app UI and the widget worker, and kept across restarts, so no path ever fires two SIRI
     * requests less than the minimum interval apart - see `RequestBudget`.
     */
    suspend fun lastSiriRequestAt(): Long = dataStore.data.first()[LAST_SIRI_REQUEST_AT_KEY] ?: 0L

    suspend fun setLastSiriRequestAt(epochMs: Long) {
        dataStore.edit { prefs -> prefs[LAST_SIRI_REQUEST_AT_KEY] = epochMs }
    }

    /**
     * Debug builds only (Settings › Débogage): draw the Plan tab's lines from the official raster
     * tiles instead of our own line map, to compare the two on the phone before the official path is
     * removed. Defaults to `false` (our line map).
     */
    val officialLineTiles: Flow<Boolean> = dataStore.data.map { prefs -> prefs[OFFICIAL_LINE_TILES_KEY] ?: false }

    suspend fun setOfficialLineTiles(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[OFFICIAL_LINE_TILES_KEY] = enabled }
    }

    private companion object {
        val OFFICIAL_LINE_TILES_KEY = booleanPreferencesKey("debug_official_line_tiles")
        val LAST_SIRI_REQUEST_AT_KEY = longPreferencesKey("last_siri_request_at")
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val LAST_ACTIVE_TAB_KEY = stringPreferencesKey("last_active_tab")
        val LOCATION_WELCOME_SEEN_KEY = booleanPreferencesKey("location_welcome_seen")

        /** Kept in sync with `Screen.Search.BASE_ROUTE` (fixed navigation contract). */
        const val DEFAULT_LAST_ACTIVE_TAB = "search"
    }
}
