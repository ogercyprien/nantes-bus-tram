package com.bustramnantes.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bustramnantes.app.data.local.AppPreferencesDataStore
import com.bustramnantes.app.domain.repository.FavoriteRepository
import com.bustramnantes.app.domain.repository.RealtimeBoardRepository
import com.bustramnantes.app.presentation.common.components.BottomNavigationBar
import com.bustramnantes.app.presentation.common.components.WelcomeLocationDialog
import com.bustramnantes.app.presentation.common.navigation.AppNavGraph
import com.bustramnantes.app.presentation.common.navigation.Screen
import com.bustramnantes.app.presentation.common.navigation.navigateToTab
import com.bustramnantes.app.presentation.common.theme.BusEtTramTheme
import com.bustramnantes.app.presentation.common.theme.ThemeMode
import com.bustramnantes.app.widget.WidgetDeepLink
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single-activity host for all Compose destinations.
 *
 * Resolves the launch tab ("Recherche on first launch; once at least one favorite
 * exists, Accueil becomes the launch tab; the app always restores the last active tab on
 * subsequent opens"), applies the persisted [ThemeMode], hosts the [BottomNavigationBar] alongside
 * [AppNavGraph], and forwards a widget tap's deep-link extras ([WidgetDeepLink]) to the Stop Detail
 * route once the nav graph exists.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appPreferencesDataStore: AppPreferencesDataStore

    @Inject
    lateinit var favoriteRepository: FavoriteRepository

    @Inject
    lateinit var realtimeBoards: RealtimeBoardRepository

    private var pendingDeepLinkIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLinkIntent = intent

        setContent {
            val themeMode by appPreferencesDataStore.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM,
            )
            BusEtTramTheme(themeMode = themeMode) {
                BusEtTramApp(
                    appPreferencesDataStore = appPreferencesDataStore,
                    favoriteRepository = favoriteRepository,
                    pendingDeepLinkIntent = pendingDeepLinkIntent,
                    onDeepLinkConsumed = { pendingDeepLinkIntent = null },
                )
            }
        }
    }

    /**
     * Brought to the foreground (launch, or back after a while): fetch the whole network's real-time
     * data in the background so the next stop opened shows real time at once. The repository
     * rate-limits it (at most every 10 min), so quick app switches cost nothing.
     */
    override fun onStart() {
        super.onStart()
        realtimeBoards.prefetchNetwork()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLinkIntent = intent
    }
}

/**
 * Root composable: resolves the start destination, hosts the [Scaffold] (bottom nav bar + nav
 * graph), persists tab switches back to [AppPreferencesDataStore.setLastActiveTab], and consumes
 * a pending widget deep-link intent once the `NavHostController` is ready.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BusEtTramApp(
    appPreferencesDataStore: AppPreferencesDataStore,
    favoriteRepository: FavoriteRepository,
    pendingDeepLinkIntent: Intent?,
    onDeepLinkConsumed: () -> Unit,
) {
    var startDestination by remember { mutableStateOf<String?>(null) }

    // Resolved exactly once per process, before the nav graph is ever composed. Note
    // Screen.Home.route/Screen.Map.route/Screen.Search.createRoute() are exactly "home"/"map"/
    // "search" (Screen.Search.createRoute() with no args returns its BASE_ROUTE), matching the
    // literal tab names AppPreferencesDataStore.setLastActiveTab expects.
    LaunchedEffect(Unit) {
        val hasStoredTab = appPreferencesDataStore.hasStoredLastActiveTab.first()
        val resolved = if (hasStoredTab) {
            when (appPreferencesDataStore.lastActiveTab.first()) {
                "home" -> Screen.Home.route
                "nearby" -> Screen.Nearby.route
                "map" -> Screen.Map.route
                else -> Screen.Search.createRoute()
            }
        } else {
            // Genuine first launch: record the decision immediately rather than relying on the
            // destination-changed listener below to observe this very first destination (whose
            // firing order relative to this effect isn't guaranteed) - otherwise a second launch
            // before the user ever manually switches tabs would re-run this same defaulting logic.
            val hasFavorites = favoriteRepository.observeFavorites().first().isNotEmpty()
            val default = if (hasFavorites) Screen.Home.route else Screen.Search.createRoute()
            appPreferencesDataStore.setLastActiveTab(default)
            default
        }
        startDestination = resolved
    }

    val resolvedStartDestination = startDestination ?: return

    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()

    // One-time first-launch welcome: explain the optional location permission, then show the system
    // prompt on top of that explanation, and never show it again once the user has chosen (§ user
    // request). Initial value `true` so the dialog never flashes before the real "seen" flag loads.
    val context = LocalContext.current
    val hasSeenLocationWelcome by appPreferencesDataStore.hasSeenLocationWelcome
        .collectAsStateWithLifecycle(initialValue = true)
    val locationAlreadyGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    // Hides the dialog immediately on choice, before the persisted flag round-trips through DataStore.
    var welcomeChoiceMade by remember { mutableStateOf(false) }
    // If permission was somehow already granted before we ever showed the welcome, just record it as
    // seen so the dialog stays suppressed (and won't resurface if the grant is later revoked).
    LaunchedEffect(hasSeenLocationWelcome, locationAlreadyGranted) {
        if (!hasSeenLocationWelcome && locationAlreadyGranted) {
            appPreferencesDataStore.setLocationWelcomeSeen()
        }
    }
    val showWelcome = !hasSeenLocationWelcome && !locationAlreadyGranted && !welcomeChoiceMade

    // Persist the active tab ("always restores the last active tab") whenever
    // navigation lands on one of the 3 bottom-nav destinations. Settings/StopDetail/Itinerary
    // results are not tabs and intentionally don't update this.
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            val tabRoute = when (destination.route) {
                Screen.Home.route -> "home"
                Screen.Nearby.route -> "nearby"
                Screen.Search.ROUTE_PATTERN -> "search"
                Screen.Map.route -> "map"
                else -> null
            }
            if (tabRoute != null) {
                coroutineScope.launch { appPreferencesDataStore.setLastActiveTab(tabRoute) }
            }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    // Widget tap deep-link: forward to Stop Detail once the graph is up, for both
    // a cold start (intent read in onCreate) and a warm start (onNewIntent).
    LaunchedEffect(pendingDeepLinkIntent, navController) {
        val stopId = pendingDeepLinkIntent?.getStringExtra(WidgetDeepLink.EXTRA_STOP_ID) ?: return@LaunchedEffect
        navController.navigate(
            Screen.StopDetail.createRoute(
                stopId = stopId,
                lineId = pendingDeepLinkIntent.getStringExtra(WidgetDeepLink.EXTRA_LINE_ID),
                direction = pendingDeepLinkIntent.getStringExtra(WidgetDeepLink.EXTRA_DIRECTION),
            ),
        )
        onDeepLinkConsumed()
    }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val showBottomBar = currentRoute == Screen.Home.route ||
        currentRoute == Screen.Nearby.route ||
        currentRoute == Screen.Search.ROUTE_PATTERN ||
        currentRoute == Screen.Map.route

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BottomNavigationBar(
                    currentDestinationRoute = currentRoute,
                    onTabSelected = { route -> navController.navigateToTab(route) },
                )
            }
        },
    ) { innerPadding ->
        AppNavGraph(
            navController = navController,
            startDestination = resolvedStartDestination,
            modifier = Modifier.padding(innerPadding),
        )
    }

    if (showWelcome) {
        WelcomeLocationDialog(
            onChoiceMade = {
                welcomeChoiceMade = true
                coroutineScope.launch { appPreferencesDataStore.setLocationWelcomeSeen() }
            },
        )
    }
}
