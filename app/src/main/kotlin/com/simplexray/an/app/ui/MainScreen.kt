package com.simplexray.an.app.ui

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.application
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.app.state.MainViewUiEvent
import com.simplexray.an.feature.logs.state.LogViewModel
import com.simplexray.an.feature.logs.state.LogViewModelFactory
import com.simplexray.an.feature.routing.ui.RoutingPresetPreviewDialog
import com.simplexray.an.feature.servers.ui.ImportServerDialog
import com.simplexray.an.feature.servers.ui.manual.ManualServerDialog
import com.simplexray.an.feature.subscriptions.ui.AddSubscriptionDialog
import com.simplexray.an.ui.navigation.BottomNavHost
import com.simplexray.an.ui.navigation.NAVIGATION_DEBOUNCE_DELAY
import com.simplexray.an.ui.navigation.ROUTE_APP_LIST
import com.simplexray.an.ui.navigation.ROUTE_SUBSCRIPTIONS
import com.simplexray.an.ui.scaffold.AppScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    appNavController: NavHostController,
    snackbarHostState: SnackbarHostState,
) {
    val bottomNavController = rememberNavController()
    val scope = rememberCoroutineScope()

    val pendingRoutingPreset by mainViewModel.pendingRoutingPreset.collectAsState()
    val routingPresetBusy by mainViewModel.routingPresetBusy.collectAsState()
    val routingPresetError by mainViewModel.routingPresetError.collectAsState()
    var clipboardBusy by remember { mutableStateOf(false) }
    var subscriptionImporting by remember { mutableStateOf(false) }
    val launchers = rememberMainScreenLaunchers(mainViewModel)

    val logViewModel: LogViewModel =
        viewModel(factory = LogViewModelFactory(mainViewModel.application))

    val callbacks =
        rememberMainScreenCallbacks(
            mainViewModel = mainViewModel,
            logViewModel = logViewModel,
            launchers = launchers,
            applicationContext = mainViewModel.application,
        )

    val shareLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) {}

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(mainViewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mainViewModel.reconcileServiceState()
                mainViewModel.refreshConfigFileList()
                mainViewModel.refreshSubscriptions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var lastNavigationTime = 0L

    LaunchedEffect(Unit) {
        mainViewModel.uiEvent.collectLatest { event ->
            when (event) {
                is MainViewUiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Short)
                }

                is MainViewUiEvent.ShareLauncher -> {
                    shareLauncher.launch(event.intent)
                }

                is MainViewUiEvent.StartService -> {
                    mainViewModel.application.startService(event.intent)
                }

                is MainViewUiEvent.RefreshConfigList -> {
                    mainViewModel.refreshConfigFileList()
                }

                is MainViewUiEvent.Navigate -> {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastNavigationTime >= NAVIGATION_DEBOUNCE_DELAY) {
                        lastNavigationTime = currentTime
                        if (event.route == ROUTE_APP_LIST) bottomNavController.navigate(event.route)
                        else appNavController.navigate(event.route)
                    }
                }
            }
        }
    }

    val clipboard = LocalClipboard.current
    var showSubscription by rememberSaveable { mutableStateOf(false) }
    var initialUrl by remember { mutableStateOf("") }
    var showServer by rememberSaveable { mutableStateOf(false) }
    var showManualServer by rememberSaveable { mutableStateOf(false) }
    var initialServer by remember { mutableStateOf("") }

    fun showClipboardMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short) }
    }

    fun paste(smart: Boolean = false) {
        scope.launch {
            try {
                val clip = clipboard.getClipEntry()?.clipData
                val text =
                    if (clip != null && clip.itemCount > 0)
                        clip
                            .getItemAt(0)
                            .coerceToText(mainViewModel.application)
                            ?.toString()
                            ?.trim()
                    else null
                if (text.isNullOrBlank()) {
                    showClipboardMessage("Буфер обмена пуст")
                    return@launch
                }
                if (
                    bottomNavController.currentDestination?.route == ROUTE_SUBSCRIPTIONS ||
                        (smart &&
                            (text.startsWith("https://") ||
                                text.startsWith("http://") ||
                                text.startsWith("simplexray://routing/", ignoreCase = true)))
                ) {
                    mainViewModel.cancelRoutingPresetImport()
                    initialUrl = text
                    showSubscription = true
                } else {
                    initialServer = text
                    showServer = true
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                showClipboardMessage("Не удалось прочитать буфер обмена")
            }
        }
    }

    fun addSubscription() {
        mainViewModel.cancelRoutingPresetImport()
        initialUrl = ""
        showSubscription = true
    }

    BottomNavHost(
        navController = bottomNavController,
        mainViewModel = mainViewModel,
        logViewModel = logViewModel,
        callbacks = callbacks,
        launchers = launchers,
        onAddSubscription = { addSubscription() },
        onPaste = { paste(smart = true) },
        onAddServer = { showManualServer = true },
    ) { entry, content ->
        AppScaffold(
            navController = bottomNavController,
            entry = entry,
            snackbarHostState = snackbarHostState,
            onAdd = {
                if (entry.destination.route == ROUTE_SUBSCRIPTIONS) addSubscription()
                else showManualServer = true
            },
            onPaste = { paste() },
            onExportLog = callbacks.onPerformExport,
            onImportRoutingPreset = {
                if (!routingPresetBusy && !clipboardBusy) {
                    clipboardBusy = true
                    mainViewModel.cancelRoutingPresetImport()
                    scope.launch {
                        try {
                            val clip = clipboard.getClipEntry()?.clipData
                            val text =
                                if (clip != null && clip.itemCount > 0)
                                    clip
                                        .getItemAt(0)
                                        .coerceToText(mainViewModel.application)
                                        ?.toString()
                                        ?.trim()
                                else null
                            if (text.isNullOrBlank()) showClipboardMessage("Буфер обмена пуст")
                            else mainViewModel.importRoutingPreset(text)
                        } catch (exception: Exception) {
                            if (exception is CancellationException) throw exception
                            showClipboardMessage("Не удалось прочитать буфер обмена")
                        } finally {
                            clipboardBusy = false
                        }
                    }
                }
            },
            onExportRoutingPreset = {
                if (!routingPresetBusy && !clipboardBusy) {
                    clipboardBusy = true
                    scope.launch {
                        try {
                            mainViewModel.exportRoutingPreset()?.let { text ->
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("Пресет роутинга", text))
                                )
                                showClipboardMessage("Пресет скопирован")
                            }
                        } catch (exception: Exception) {
                            if (exception is CancellationException) throw exception
                            showClipboardMessage("Не удалось скопировать пресет")
                        } finally {
                            clipboardBusy = false
                        }
                    }
                }
            },
            routingPresetBusy = routingPresetBusy || clipboardBusy,
            content = content,
        )
    }
    if (showSubscription) {
        AddSubscriptionDialog(
            initialUrl = initialUrl,
            onDismiss = { if (!subscriptionImporting) showSubscription = false },
            onConfirm = { name, url ->
                if (!subscriptionImporting && !routingPresetBusy) {
                    subscriptionImporting = true
                    scope.launch {
                        try {
                            if (mainViewModel.importSubscription(name, url))
                                showSubscription = false
                        } finally {
                            subscriptionImporting = false
                        }
                    }
                }
            },
            busy = subscriptionImporting || routingPresetBusy,
            error = routingPresetError,
        )
    }
    if (showManualServer) {
        ManualServerDialog(
            onDismiss = { showManualServer = false },
            onImport = { content, name -> mainViewModel.importServer(content, name) },
        )
    }
    if (showServer) {
        ImportServerDialog(
            initialServer,
            onDismiss = { showServer = false },
            onImport = { content, name -> mainViewModel.importServer(content, name) },
        )
    }
    pendingRoutingPreset?.let { preset ->
        RoutingPresetPreviewDialog(
            preset = preset,
            onDismiss = mainViewModel::cancelRoutingPresetImport,
            onApply = {
                if (!routingPresetBusy) {
                    scope.launch {
                        if (mainViewModel.applyRoutingPreset()) {
                            bottomNavController.navigate(
                                "settings-detail/" + Uri.encode("Роутинг")
                            ) {
                                launchSingleTop = true
                            }
                        }
                    }
                }
            },
            busy = routingPresetBusy,
            error = routingPresetError,
        )
    }
}
