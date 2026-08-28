package com.bustramnantes.app.presentation.common.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.bustramnantes.app.presentation.common.navigation.Screen

/**
 * The 4 bottom-nav tabs: Favoris / À proximité / Recherche / Plan.
 *
 * [destinationRoute] is the route *pattern* as registered in [androidx.navigation.NavGraphBuilder]
 * (what `NavDestination.route` holds, e.g. [Screen.Search.ROUTE_PATTERN] with its query
 * placeholders) - compare against it for tab-selected highlighting. [navigateRoute] is the
 * concrete string to pass to `navController.navigate(...)` when the tab is tapped.
 */
enum class BottomNavTab(
    val navigateRoute: String,
    val destinationRoute: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(Screen.Home.route, Screen.Home.route, "Favoris", Icons.Filled.Star, Icons.Outlined.Star),
    NEARBY(Screen.Nearby.route, Screen.Nearby.route, "À proximité", Icons.Filled.LocationOn, Icons.Outlined.LocationOn),
    SEARCH(Screen.Search.createRoute(), Screen.Search.ROUTE_PATTERN, "Recherche", Icons.Filled.Search, Icons.Outlined.Search),
    MAP(Screen.Map.route, Screen.Map.route, "Plan", Icons.Filled.Map, Icons.Outlined.Map),
}

/**
 * Material3 [NavigationBar] hosting the tabs. [currentDestinationRoute] should be
 * `navController.currentBackStackEntryAsState().value?.destination?.route` (the *pattern*, not
 * the resolved instance) so that e.g. Search stays highlighted regardless of which prefill args
 * are present.
 */
@Composable
fun BottomNavigationBar(
    currentDestinationRoute: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        BottomNavTab.entries.forEach { tab ->
            val selected = currentDestinationRoute == tab.destinationRoute
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab.navigateRoute) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label) },
            )
        }
    }
}
