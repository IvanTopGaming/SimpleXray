package com.simplexray.an.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.app.ui.MainScreenCallbacks
import com.simplexray.an.app.ui.MainScreenLaunchers
import com.simplexray.an.feature.apps.state.AppListViewModel
import com.simplexray.an.feature.apps.ui.AppListScreen
import com.simplexray.an.feature.dashboard.ui.DashboardScreen
import com.simplexray.an.feature.logs.state.LogViewModel
import com.simplexray.an.feature.logs.ui.LogScreen
import com.simplexray.an.feature.servers.ui.ConfigScreen
import com.simplexray.an.feature.settings.ui.SettingsHomeScreen
import com.simplexray.an.feature.settings.ui.SettingsScreen
import com.simplexray.an.feature.subscriptions.ui.SubscriptionsScreen
import com.simplexray.an.service.TProxyService
import com.simplexray.an.ui.scaffold.navigateToRoute

@Composable
fun BottomNavHost(
    navController: NavHostController,
    mainViewModel: MainViewModel,
    logViewModel: LogViewModel,
    callbacks: MainScreenCallbacks,
    launchers: MainScreenLaunchers,
    onAddSubscription: () -> Unit,
    onPaste: () -> Unit,
    onAddServer: () -> Unit = {},
    scaffold: @Composable (NavBackStackEntry, @Composable (PaddingValues) -> Unit) -> Unit,
) {
    var subscriptionFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var filterRequest by rememberSaveable { mutableIntStateOf(0) }
    ImmediateNavHost(
        navController,
        startDestination = ROUTE_STATS,
        routes =
            listOf(
                ROUTE_STATS,
                ROUTE_CONFIG,
                ROUTE_SUBSCRIPTIONS,
                ROUTE_SETTINGS,
                "settings-detail/{section}",
                ROUTE_LOG,
                ROUTE_APP_LIST,
            ),
        chrome = { entry, content ->
            scaffold(entry) { paddingValues ->
                Box(Modifier.padding(paddingValues)) { content() }
            }
        },
    ) { entry ->
        when (entry.destination.route) {
            ROUTE_STATS -> {
                DashboardScreen(
                    mainViewModel,
                    onSwitchVpn = callbacks.onSwitchVpnService,
                    onServers = { navigateToRoute(navController, ROUTE_CONFIG) },
                    onAddSubscription = onAddSubscription,
                    onPaste = onPaste,
                    onAddServer = onAddServer,
                )
            }
            ROUTE_CONFIG -> {
                ConfigScreen(
                    onReloadConfig = {
                        mainViewModel.startTProxyService(TProxyService.ACTION_RELOAD_CONFIG)
                    },
                    onEditConfigClick = { mainViewModel.editConfig(it.absolutePath) },
                    onDeleteConfigClick = callbacks.onDeleteConfigClick,
                    mainViewModel = mainViewModel,
                    listState = rememberLazyListState(),
                    subscriptionFilter = subscriptionFilter,
                    filterRequest = filterRequest,
                    onClearFilter = { subscriptionFilter = null },
                )
            }
            ROUTE_SUBSCRIPTIONS -> {
                SubscriptionsScreen(
                    mainViewModel,
                    onAddSubscription,
                    onServers = {
                        subscriptionFilter = it
                        filterRequest++
                        navigateToRoute(navController, ROUTE_CONFIG)
                    },
                )
            }
            ROUTE_SETTINGS -> {
                SettingsHomeScreen(
                    mainViewModel,
                    onSection = {
                        navController.navigate("settings-detail/" + Uri.encode(it))
                    },
                    onLogs = { navController.navigate(ROUTE_LOG) },
                )
            }
            "settings-detail/{section}" -> {
                SettingsScreen(
                    mainViewModel = mainViewModel,
                    geoipFilePickerLauncher = launchers.geoipFilePickerLauncher,
                    geositeFilePickerLauncher = launchers.geositeFilePickerLauncher,
                    scrollState = rememberScrollState(),
                    section = entry.arguments?.getString("section"),
                )
            }
            ROUTE_LOG -> {
                LogScreen(logViewModel, rememberLazyListState())
            }
            ROUTE_APP_LIST -> {
                AppListScreen(
                    viewModel =
                        viewModel(
                            factory =
                                viewModelFactory {
                                    initializer {
                                        AppListViewModel(mainViewModel.getApplication())
                                    }
                                }
                        ),
                    onBackClick = { navController.popBackStack() },
                    embedded = true,
                )
            }
        }
    }
}
