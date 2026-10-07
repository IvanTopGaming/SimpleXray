package com.simplexray.an.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.LocalOwnersProvider
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.get

@Composable
internal fun ImmediateNavHost(
    navController: NavHostController,
    startDestination: String,
    routes: List<String>,
    chrome: @Composable (NavBackStackEntry, @Composable () -> Unit) -> Unit,
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    val owner = checkNotNull(LocalViewModelStoreOwner.current)
    val lifecycleOwner = LocalLifecycleOwner.current
    navController.setViewModelStore(owner.viewModelStore)
    val graph =
        remember(navController, startDestination, routes) {
            navController.createGraph(startDestination) {
                routes.forEach { route -> composable(route) {} }
            }
        }
    navController.graph = graph
    DisposableEffect(navController, lifecycleOwner) {
        navController.setLifecycleOwner(lifecycleOwner)
        onDispose {}
    }
    val navigator = navController.navigatorProvider.get<ComposeNavigator>("composable")
    val backStack by navigator.backStack.collectAsState()
    val visibleEntries = navController.visibleEntries.collectAsState().value
    val entry = backStack.lastOrNull()
    val stateHolder = rememberSaveableStateHolder()
    BackHandler(enabled = backStack.size > 1) { navController.popBackStack() }
    if (entry != null) {
        chrome(entry) {
            key(entry.id) {
                entry.LocalOwnersProvider(stateHolder) { content(entry) }
            }
        }
    }
    SideEffect {
        visibleEntries.forEach(navigator::onTransitionComplete)
    }
    DisposableEffect(navigator) {
        onDispose {
            navController.visibleEntries.value.forEach(navigator::onTransitionComplete)
        }
    }
}
