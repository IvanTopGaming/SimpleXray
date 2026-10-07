package com.simplexray.an.feature.settings.ui

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.feature.dns.ui.DnsSettingsSection
import com.simplexray.an.feature.kernel.ui.KernelSettingsSection
import com.simplexray.an.feature.logs.ui.LoggingSettingsSection
import com.simplexray.an.feature.profile.ui.ProfileSettingsSection
import com.simplexray.an.feature.routing.server.RoutingServerCatalog
import com.simplexray.an.feature.routing.server.RoutingServerOption
import com.simplexray.an.feature.routing.ui.RoutingSettingsSection
import com.simplexray.an.feature.servers.ui.PingSettingsSection
import com.simplexray.an.feature.subscriptions.data.SubscriptionManager
import com.simplexray.an.feature.subscriptions.ui.SubscriptionSettingsSection
import com.simplexray.an.ui.components.labGap
import com.simplexray.an.ui.components.labHorizontalPadding
import com.simplexray.an.ui.components.settings.LabEditableSetting
import com.simplexray.an.ui.components.settings.LabSettingDivider
import com.simplexray.an.ui.components.settings.LabSettingGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    geoipFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    geositeFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    scrollState: androidx.compose.foundation.ScrollState,
    section: String? = null,
) {
    val context = LocalContext.current
    val settingsState by mainViewModel.settingsState.collectAsStateWithLifecycle()
    val connected by mainViewModel.isServiceEnabled.collectAsStateWithLifecycle()
    val geoipProgress by mainViewModel.geoipDownloadProgress.collectAsStateWithLifecycle()
    val geositeProgress by mainViewModel.geositeDownloadProgress.collectAsStateWithLifecycle()
    val isCheckingForUpdates by mainViewModel.isCheckingForUpdates.collectAsStateWithLifecycle()
    val newVersionTag by mainViewModel.newVersionAvailable.collectAsStateWithLifecycle()

    val vpnDisabled = settingsState.switches.disableVpn

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    var editingRuleFile by remember { mutableStateOf<String?>(null) }
    var ruleFileUrl by remember { mutableStateOf("") }

    if (editingRuleFile != null) {
        GeodataUrlEditor(
            ruleFileUrl,
            editingRuleFile,
            sheetState,
            scope,
            onUrlChange = { ruleFileUrl = it },
            onDismiss = { editingRuleFile = null },
            onDownload = mainViewModel::downloadRuleFile,
        )
    }

    if (newVersionTag != null) {
        AppUpdateDialog(mainViewModel, newVersionTag)
    }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = labHorizontalPadding(), vertical = labGap()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (section == null || section == "Подключение") {
            ConnectionSettingsSection(mainViewModel, settingsState, vpnDisabled, sheetState, scope)
        }
        if (section == null || section == "Подписки") {
            SubscriptionSettingsSection(mainViewModel.prefs)
        }
        if (section == null || section == "Пинг") {
            PingSettingsSection(
                settingsState.probeMethod,
                settingsState.connectivityTestTarget,
                settingsState.connectivityTestTimeout,
                mainViewModel::updateProbeMethod,
                mainViewModel::updateConnectivityTestTarget,
                mainViewModel::updateConnectivityTestTimeout,
                sheetState,
                scope,
            )
        }
        if (section == null || section == "DNS") {
            DnsSettingsSection(mainViewModel.prefs)
            LabSettingGroup("DNS интерфейса Android") {
                LabEditableSetting(
                    "DNS · IPv4",
                    settingsState.dnsIpv4,
                    mainViewModel::updateDnsIpv4,
                    sheetState,
                    scope,
                    help = "DNS-адрес IPv4, который VPN сообщает Android.",
                    enabled = !vpnDisabled,
                    keyboardType = KeyboardType.Uri,
                )
                LabSettingDivider()
                LabEditableSetting(
                    "DNS · IPv6",
                    settingsState.dnsIpv6,
                    mainViewModel::updateDnsIpv6,
                    sheetState,
                    scope,
                    help = "DNS-адрес IPv6, который VPN сообщает Android.",
                    enabled = settingsState.switches.ipv6Enabled && !vpnDisabled,
                    keyboardType = KeyboardType.Uri,
                )
            }
        }
        if (section == null || section == "Роутинг") {
            val configFiles by mainViewModel.configFiles.collectAsStateWithLifecycle()
            val subscriptions by mainViewModel.subscriptions.collectAsStateWithLifecycle()
            val serverOptions by
                produceState<List<RoutingServerOption>>(emptyList(), configFiles, subscriptions) {
                    value =
                        withContext(Dispatchers.IO) {
                            SubscriptionManager.configMutex.withLock {
                                RoutingServerCatalog.options(configFiles, subscriptions)
                            }
                        }
                }
            RoutingSettingsSection(mainViewModel.routingEditor, serverOptions)
        }
        if (section == null || section == "Входящие подключения") {
            InboundSettingsSection(mainViewModel, settingsState, vpnDisabled, sheetState, scope)
        }
        if (section == null || section == "Ядро и конфигурация") {
            KernelSettingsSection(mainViewModel.prefs)
            LoggingSettingsSection(mainViewModel.prefs)
        }
        if (section == null || section == "Профиль и JSON") {
            ProfileSettingsSection(mainViewModel)
        }
        if (section == null || section == "Базы маршрутизации") {
            GeodataSettingsSection(
                mainViewModel,
                settingsState,
                geoipProgress,
                geositeProgress,
                geoipFilePickerLauncher,
                geositeFilePickerLauncher,
                onEditUrl = { fileName, currentUrl ->
                    ruleFileUrl = currentUrl
                    editingRuleFile = fileName
                    scope.launch { sheetState.show() }
                },
            )
        }
        if (section == null || section == "О приложении") {
            AboutSettingsSection(mainViewModel, settingsState, isCheckingForUpdates)
        }
    }
}
