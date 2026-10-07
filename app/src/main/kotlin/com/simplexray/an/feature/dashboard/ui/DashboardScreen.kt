package com.simplexray.an.feature.dashboard.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.feature.dashboard.model.TrafficRateTracker
import com.simplexray.an.feature.servers.model.ServerDetails
import com.simplexray.an.ui.components.labGap
import com.simplexray.an.ui.components.labHorizontalPadding
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    mainViewModel: MainViewModel,
    onSwitchVpn: () -> Unit,
    onServers: () -> Unit,
    onAddSubscription: () -> Unit,
    onPaste: () -> Unit,
    onAddServer: () -> Unit = onServers,
) {
    val stats by mainViewModel.coreStatsState.collectAsStateWithLifecycle()
    val connected by mainViewModel.isServiceEnabled.collectAsStateWithLifecycle()
    val preparation by mainViewModel.connectionPreparation.collectAsStateWithLifecycle()
    val ready by mainViewModel.controlMenuClickable.collectAsStateWithLifecycle()
    val files by mainViewModel.configFiles.collectAsStateWithLifecycle()
    val selected by mainViewModel.selectedConfigFile.collectAsStateWithLifecycle()
    val subscriptions by mainViewModel.subscriptions.collectAsStateWithLifecycle()
    val owners by mainViewModel.subscriptionByFile.collectAsStateWithLifecycle()
    val checks by mainViewModel.serverChecks.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var download by remember(connected, selected) { mutableLongStateOf(0) }
    var upload by remember(connected, selected) { mutableLongStateOf(0) }
    val serverDetails by mainViewModel.serverDetails.collectAsStateWithLifecycle()
    val details = serverDetails[selected] ?: ServerDetails()
    LaunchedEffect(lifecycleOwner, connected, selected) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val rates = TrafficRateTracker()
            while (connected) {
                val current = mainViewModel.updateCoreStats()
                val speed = rates.update(current, SystemClock.elapsedRealtime())
                download = speed.downlink
                upload = speed.uplink
                delay(1000)
            }
        }
    }
    val gap = labGap()
    val density = LocalDensity.current
    var selectionHeight by remember { mutableStateOf(94.dp) }
    var speedHeight by remember { mutableStateOf(72.dp) }
    var totalHeight by remember { mutableStateOf(30.dp) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val available = maxHeight
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = labHorizontalPadding())
                .padding(top = gap, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (files.isEmpty() && !connected && ready) {
                DashboardWelcome(
                    onAddSubscription,
                    onPaste,
                    onAddServer,
                    Modifier.fillMaxWidth().heightIn(min = maxOf(300.dp, available - gap - 24.dp)),
                )
            } else {
                DashboardServerCard(
                    selected,
                    details,
                    subscriptions.find { it.id == owners[selected?.name] }?.displayName,
                    checks.results[selected?.absolutePath],
                    selected?.absolutePath in checks.checking,
                    onServers,
                    Modifier.fillMaxWidth().onSizeChanged {
                        selectionHeight = with(density) { it.height.toDp() }
                    },
                )
                DashboardConnectionControl(
                    connected,
                    ready,
                    preparation,
                    if (selected == null && !connected) onServers else onSwitchVpn,
                    Modifier.fillMaxWidth()
                        .heightIn(
                            min =
                                maxOf(
                                    220.dp,
                                    available -
                                        gap * 4 -
                                        24.dp -
                                        selectionHeight -
                                        speedHeight -
                                        totalHeight,
                                )
                        )
                        .padding(vertical = 24.dp),
                )
                DashboardSpeeds(
                    connected,
                    download,
                    upload,
                    Modifier.fillMaxWidth()
                        .testTag("live-speeds")
                        .onSizeChanged { speedHeight = with(density) { it.height.toDp() } }
                        .padding(vertical = 12.dp),
                    trafficStatsEnabled = stats.trafficStatsEnabled,
                )
                DashboardTotals(
                    connected,
                    stats,
                    Modifier.fillMaxWidth().testTag("session-totals").onSizeChanged {
                        totalHeight = with(density) { it.height.toDp() }
                    },
                )
            }
        }
    }
}
