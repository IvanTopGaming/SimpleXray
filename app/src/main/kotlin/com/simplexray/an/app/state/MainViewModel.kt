package com.simplexray.an.app.state

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.simplexray.an.R
import com.simplexray.an.core.files.FileManager
import com.simplexray.an.core.network.socks.LocalProxyEndpoint
import com.simplexray.an.feature.dashboard.model.CoreStatsState
import com.simplexray.an.feature.dashboard.model.TrafficState
import com.simplexray.an.feature.dashboard.state.ConnectionController
import com.simplexray.an.feature.routing.state.RoutingEditor
import com.simplexray.an.feature.routing.state.RoutingPresetController
import com.simplexray.an.feature.servers.model.ProbeMethod
import com.simplexray.an.feature.servers.model.ServerDetails
import com.simplexray.an.feature.servers.model.serverDetails
import com.simplexray.an.feature.servers.state.ConfigFilesController
import com.simplexray.an.feature.settings.state.SettingsController
import com.simplexray.an.feature.settings.state.SettingsState
import com.simplexray.an.feature.settings.state.geodata.GeodataController
import com.simplexray.an.feature.settings.state.updates.UpdatesController
import com.simplexray.an.feature.subscriptions.data.SubscriptionManager
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.feature.subscriptions.state.SubscriptionSyncState
import com.simplexray.an.feature.subscriptions.state.SubscriptionsController
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.navigation.ROUTE_APP_LIST
import com.simplexray.an.ui.navigation.ROUTE_CONFIG_EDIT
import com.simplexray.an.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val prefs: Preferences = Preferences(application)
    private val fileManager = FileManager(application, prefs)
    private val configUpdateMutex = SubscriptionManager.configMutex
    private val _uiEvent = Channel<MainViewUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()
    var reloadView: (() -> Unit)? = null

    val routingEditor =
        RoutingEditor(
            read = { prefs.routingSettingsJson },
            write = { prefs.routingSettingsJson = it },
        )

    private val connection: ConnectionController =
        ConnectionController(
            application = application,
            prefs = prefs,
            scope = viewModelScope,
            selectedConfigFile = { configs.selectedConfigFile.value },
            sendEvent = ::sendEvent,
        )

    private val configs =
        ConfigFilesController(
            application = application,
            prefs = prefs,
            scope = viewModelScope,
            fileManager = fileManager,
            configUpdateMutex = configUpdateMutex,
            isServiceEnabled = { connection.isServiceEnabled.value },
            sendEvent = ::sendEvent,
        )

    private val settings =
        SettingsController(
            application = application,
            prefs = prefs,
            fileManager = fileManager,
            resetServerChecks = configs::resetServerChecks,
            reloadView = { reloadView?.invoke() },
        )

    private val geodata =
        GeodataController(
            application = application,
            prefs = prefs,
            scope = viewModelScope,
            fileManager = fileManager,
            isServiceEnabled = { connection.isServiceEnabled.value },
            refreshRuleFileState = settings::refreshRuleFileState,
            updateSettingsState = settings::updateSettingsState,
            sendEvent = ::sendEvent,
        )

    private val subscriptionManager =
        SubscriptionManager(application, prefs) { connection.isServiceEnabled.value }

    private val routingPresets =
        RoutingPresetController(
            prefs = prefs,
            fileManager = fileManager,
            subscriptionManager = subscriptionManager,
            configUpdateMutex = configUpdateMutex,
            routingEditor = routingEditor,
            refreshConfigFileList = { configs.refreshConfigFileList() },
            updateSettingsState = settings::updateSettingsState,
            cancelDownloadsAndJoin = geodata::cancelDownloadsAndJoin,
            sendEvent = ::sendEvent,
        )

    private val subscriptionState =
        SubscriptionsController(
            application = application,
            prefs = prefs,
            scope = viewModelScope,
            subscriptionManager = subscriptionManager,
            configUpdateMutex = configUpdateMutex,
            routingPresets = routingPresets,
            refreshConfigFileList = { configs.refreshConfigFileList() },
            selectedConfigFile = { configs.selectedConfigFile.value },
            updateSelectedConfigFile = configs::updateSelectedConfigFile,
            isServiceEnabled = { connection.isServiceEnabled.value },
            stopTProxyService = connection::stopTProxyService,
        )

    private val updates =
        UpdatesController(
            application = application,
            prefs = prefs,
            scope = viewModelScope,
            isServiceEnabled = { connection.isServiceEnabled.value },
            sendEvent = ::sendEvent,
        )

    val coreStatsState: StateFlow<CoreStatsState> = connection.coreStatsState

    val controlMenuClickable: StateFlow<Boolean> = connection.controlMenuClickable

    val connectionPreparation = connection.connectionPreparation

    val isServiceEnabled: StateFlow<Boolean> = connection.isServiceEnabled

    val serverChecks = configs.serverChecks

    val configFiles: StateFlow<List<File>> = configs.configFiles

    val serverDetails: StateFlow<Map<File, ServerDetails>> = configs.serverDetails

    val selectedConfigFile: StateFlow<File?> = configs.selectedConfigFile

    val settingsState: StateFlow<SettingsState> = settings.settingsState

    val geoipDownloadProgress: StateFlow<String?> = geodata.geoipDownloadProgress

    val geositeDownloadProgress: StateFlow<String?> = geodata.geositeDownloadProgress

    val pendingRoutingPreset = routingPresets.pendingRoutingPreset

    val routingPresetBusy = routingPresets.routingPresetBusy

    val routingPresetError = routingPresets.routingPresetError

    val subscriptions: StateFlow<List<Subscription>> = subscriptionState.subscriptions

    val autoUpdateSubscriptions: StateFlow<Boolean> = subscriptionState.autoUpdateSubscriptions

    val subscriptionSync: StateFlow<Map<String, SubscriptionSyncState>> =
        subscriptionState.subscriptionSync

    val subscriptionByFile: StateFlow<Map<String, String>> = subscriptionState.subscriptionByFile

    val isCheckingForUpdates: StateFlow<Boolean> = updates.isCheckingForUpdates

    val newVersionAvailable: StateFlow<String?> = updates.newVersionAvailable

    init {
        subscriptionState.registerObserver()
        registerTProxyServiceReceivers()
        Log.d(TAG, "MainViewModel initialized.")
        setupGlobalSocksAuthenticator()
        viewModelScope.launch(Dispatchers.IO) {
            reconcileServiceState()
            settings.updateSettingsState()
            loadKernelVersion()
            refreshConfigFileList()
            refreshSubscriptions()
        }
    }

    override fun onCleared() {
        configs.cancelServerChecks()
        subscriptionState.unregisterObserver()
        unregisterTProxyServiceReceivers()
        connection.close()
        super.onCleared()
    }

    private fun loadKernelVersion() = settings.loadKernelVersion()

    private fun sendEvent(event: MainViewUiEvent) {
        _uiEvent.trySend(event)
    }

    fun setControlMenuClickable(isClickable: Boolean) =
        connection.setControlMenuClickable(isClickable)

    fun setServiceEnabled(enabled: Boolean) = connection.setServiceEnabled(enabled)

    fun reconcileServiceState() = connection.reconcileServiceState()

    suspend fun updateCoreStats(): TrafficState? = connection.updateCoreStats()

    fun startTProxyService(action: String) = connection.startTProxyService(action)

    fun stopTProxyService() = connection.stopTProxyService()

    fun prepareAndStartVpn(vpnPrepareLauncher: ActivityResultLauncher<Intent>) =
        connection.prepareAndStartVpn(vpnPrepareLauncher)

    fun registerTProxyServiceReceivers() = connection.registerTProxyServiceReceivers()

    fun unregisterTProxyServiceReceivers() = connection.unregisterTProxyServiceReceivers()

    fun checkServers(files: List<File>) = configs.checkServers(files)

    fun cancelServerChecks() = configs.cancelServerChecks()

    suspend fun createConfigFile(): String? = configs.createConfigFile()

    suspend fun importConfigFromClipboard(): String? = configs.importConfigFromClipboard()

    suspend fun deleteConfigFile(file: File, callback: () -> Unit) =
        configs.deleteConfigFile(file, callback)

    fun refreshConfigFileList() = configs.refreshConfigFileList()

    fun updateSelectedConfigFile(file: File?) = configs.updateSelectedConfigFile(file)

    fun testConnectivity() = configs.testConnectivity()

    fun updateSocksAddress(addressString: String): Boolean =
        settings.updateSocksAddress(addressString)

    fun updateSocksPort(portString: String): Boolean = settings.updateSocksPort(portString)

    fun updateHttpPort(value: String): Boolean = settings.updateHttpPort(value)

    fun setSocksUdpEnabled(enabled: Boolean) = settings.setSocksUdpEnabled(enabled)

    fun setAllowLanAccess(enabled: Boolean) = settings.setAllowLanAccess(enabled)

    fun updateSocksUser(userString: String): Boolean = settings.updateSocksUser(userString)

    fun updateSocksPass(passString: String): Boolean = settings.updateSocksPass(passString)

    fun updateDnsIpv4(ipv4Addr: String): Boolean = settings.updateDnsIpv4(ipv4Addr)

    fun updateDnsIpv6(ipv6Addr: String): Boolean = settings.updateDnsIpv6(ipv6Addr)

    fun setIpv6Enabled(enabled: Boolean) = settings.setIpv6Enabled(enabled)

    fun setHttpProxyEnabled(enabled: Boolean) = settings.setHttpProxyEnabled(enabled)

    fun setBypassLanEnabled(enabled: Boolean) = settings.setBypassLanEnabled(enabled)

    fun setAutoStartEnabled(enabled: Boolean) = settings.setAutoStartEnabled(enabled)

    fun setDisableVpnEnabled(enabled: Boolean) = settings.setDisableVpnEnabled(enabled)

    fun setTheme(mode: ThemeMode) = settings.setTheme(mode)

    fun updateConnectivityTestTarget(target: String) = settings.updateConnectivityTestTarget(target)

    fun updateConnectivityTestTimeout(timeout: String) =
        settings.updateConnectivityTestTimeout(timeout)

    fun updateProbeMethod(method: ProbeMethod) = settings.updateProbeMethod(method)

    fun importRuleFile(uri: Uri, fileName: String) = geodata.importRuleFile(uri, fileName)

    fun cancelDownload(fileName: String) = geodata.cancelDownload(fileName)

    fun downloadRuleFile(url: String, fileName: String) = geodata.downloadRuleFile(url, fileName)

    suspend fun importServer(content: String, name: String? = null): Boolean =
        routingPresets.importServer(content, name)

    suspend fun importRoutingPreset(content: String): Boolean =
        routingPresets.importRoutingPreset(content)

    suspend fun exportRoutingPreset(): String? = routingPresets.exportRoutingPreset()

    fun cancelRoutingPresetImport() = routingPresets.cancelRoutingPresetImport()

    suspend fun applyRoutingPreset(): Boolean = routingPresets.applyRoutingPreset()

    fun refreshSubscriptions() = subscriptionState.refreshSubscriptions()

    fun updateAutoUpdateSubscriptions(enabled: Boolean) =
        subscriptionState.updateAutoUpdateSubscriptions(enabled)

    fun addSubscription(name: String, url: String) = subscriptionState.addSubscription(name, url)

    suspend fun importSubscription(name: String, url: String): Boolean =
        subscriptionState.importSubscription(name, url)

    fun syncSubscription(id: String) = subscriptionState.syncSubscription(id)

    fun updateSubscriptionUrl(id: String, url: String) =
        subscriptionState.updateSubscriptionUrl(id, url)

    fun deleteSubscription(id: String) = subscriptionState.deleteSubscription(id)

    fun checkForUpdates() = updates.checkForUpdates()

    fun downloadNewVersion(versionTag: String) = updates.downloadNewVersion(versionTag)

    fun clearNewVersionAvailable() = updates.clearNewVersionAvailable()

    private fun setupGlobalSocksAuthenticator() {
        java.net.Authenticator.setDefault(
            object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                    return LocalProxyEndpoint.active(prefs)
                        .authentication(requestingHost, requestingPort, requestingProtocol)
                }
            }
        )
    }

    suspend fun handleSharedContent(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (importServer(content)) {
                if (pendingRoutingPreset.value == null) {
                    _uiEvent.trySend(
                        MainViewUiEvent.ShowSnackbar(application.getString(R.string.import_success))
                    )
                }
            } else {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        application.getString(R.string.invalid_config_format)
                    )
                )
            }
        }
    }

    fun showExportFailedSnackbar() {
        _uiEvent.trySend(
            MainViewUiEvent.ShowSnackbar(application.getString(R.string.export_failed))
        )
    }

    fun editConfig(filePath: String) {
        viewModelScope.launch {
            _uiEvent.trySend(
                MainViewUiEvent.Navigate("$ROUTE_CONFIG_EDIT?file=" + Uri.encode(filePath))
            )
        }
    }

    fun shareIntent(chooserIntent: Intent, packageManager: PackageManager) {
        viewModelScope.launch {
            if (chooserIntent.resolveActivity(packageManager) != null) {
                _uiEvent.trySend(MainViewUiEvent.ShareLauncher(chooserIntent))
                Log.d(TAG, "Export intent resolved and started.")
            } else {
                Log.w(TAG, "No activity found to handle export intent.")
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.no_app_for_export))
                )
            }
        }
    }

    fun navigateToAppList() {
        viewModelScope.launch { _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_APP_LIST)) }
    }

    companion object {
        fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean =
            ConnectionController.isServiceRunning(context, serviceClass)
    }
}
