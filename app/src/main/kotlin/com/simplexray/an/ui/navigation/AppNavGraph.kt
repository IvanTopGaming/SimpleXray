package com.simplexray.an.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.simplexray.an.ui.navigation.ROUTE_APP_LIST
import com.simplexray.an.ui.navigation.ROUTE_CONFIG_EDIT
import com.simplexray.an.ui.navigation.ROUTE_MAIN
import com.simplexray.an.feature.apps.ui.AppListScreen
import com.simplexray.an.feature.servers.ui.ConfigEditScreen
import com.simplexray.an.app.ui.MainScreen
import com.simplexray.an.ui.theme.DisableDialogWindowAnimations
import com.simplexray.an.feature.apps.state.AppListViewModel
import com.simplexray.an.feature.servers.state.ConfigEditViewModel
import com.simplexray.an.app.state.MainViewModel

@Composable
fun AppNavHost(mainViewModel: MainViewModel) {
    val navController = rememberNavController()
    val application = mainViewModel.getApplication<android.app.Application>()

    NavHost(
        navController = navController,
        startDestination = ROUTE_MAIN,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(route = ROUTE_MAIN) {
            MainScreen(
                mainViewModel = mainViewModel,
                appNavController = navController,
                snackbarHostState = remember { SnackbarHostState() },
            )
        }

        composable(route = ROUTE_APP_LIST) {
            AppListScreen(
                viewModel =
                    viewModel(
                        factory = viewModelFactory { initializer { AppListViewModel(application) } }
                    ),
                onBackClick = { navController.popBackStack() },
            )
        }

        dialog(
            route = "$ROUTE_CONFIG_EDIT?file={file}",
            arguments =
                listOf(
                    navArgument("file") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
            dialogProperties =
                DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) { entry ->
            DisableDialogWindowAnimations()
            val filePath = entry.arguments?.getString("file")
            if (filePath == null) {
                LaunchedEffect(Unit) { navController.popBackStack(ROUTE_MAIN, false) }
                return@dialog
            }
            ConfigEditScreen(
                onBackClick = { navController.popBackStack() },
                snackbarHostState = remember { SnackbarHostState() },
                viewModel =
                    viewModel(
                        factory =
                            viewModelFactory {
                                initializer {
                                    ConfigEditViewModel(application, filePath, mainViewModel.prefs)
                                }
                            }
                    ),
            )
        }
    }
}
