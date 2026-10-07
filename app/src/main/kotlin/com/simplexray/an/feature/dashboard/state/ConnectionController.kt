package com.simplexray.an.feature.dashboard.state

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.application
import com.simplexray.an.R
import com.simplexray.an.app.state.MainViewUiEvent
import com.simplexray.an.core.runtime.stats.CoreStatsClient
import com.simplexray.an.feature.dashboard.model.CoreStatsState
import com.simplexray.an.feature.dashboard.model.TrafficState
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.TProxyService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"

internal class ConnectionController(
    private val application: Application,
    private val prefs: Preferences,
    private val scope: CoroutineScope,
    private val selectedConfigFile: () -> File?,
    private val sendEvent: (MainViewUiEvent) -> Unit,
) {
    private var coreStatsClient: CoreStatsClient? = null

    private val _coreStatsState =
        MutableStateFlow(CoreStatsState(trafficStatsEnabled = prefs.activeTrafficStatsEnabled))

    val coreStatsState: StateFlow<CoreStatsState> = _coreStatsState.asStateFlow()

    private val _controlMenuClickable = MutableStateFlow(true)

    val controlMenuClickable: StateFlow<Boolean> = _controlMenuClickable.asStateFlow()

    private val _connectionPreparation = MutableStateFlow<String?>(null)

    val connectionPreparation = _connectionPreparation.asStateFlow()

    private val _isServiceEnabled = MutableStateFlow(false)

    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

    private val startReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(TAG, "Service started")
                setServiceEnabled(true)
                setControlMenuClickable(true)
            }
        }

    private val preparationReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val message = intent.getStringExtra(TProxyService.EXTRA_PREPARATION) ?: return
                if (intent.getBooleanExtra(TProxyService.EXTRA_PREPARATION_ERROR, false)) {
                    _connectionPreparation.value = null
                    setControlMenuClickable(true)
                    sendEvent(MainViewUiEvent.ShowSnackbar(message))
                } else {
                    _connectionPreparation.value = message
                    setControlMenuClickable(false)
                }
            }
        }

    private val stopReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(TAG, "Service stopped")
                setServiceEnabled(false)
                setControlMenuClickable(true)
                _coreStatsState.value =
                    CoreStatsState(trafficStatsEnabled = prefs.activeTrafficStatsEnabled)
                coreStatsClient?.close()
                coreStatsClient = null
            }
        }

    fun setControlMenuClickable(isClickable: Boolean) {
        _controlMenuClickable.value = isClickable
    }

    fun setServiceEnabled(enabled: Boolean) {
        _connectionPreparation.value = null
        _isServiceEnabled.value = enabled
        if (enabled) {
            _coreStatsState.value =
                _coreStatsState.value.copy(trafficStatsEnabled = prefs.activeTrafficStatsEnabled)
        }
        if (!enabled) {
            _coreStatsState.value =
                CoreStatsState(trafficStatsEnabled = prefs.activeTrafficStatsEnabled)
            coreStatsClient?.close()
            coreStatsClient = null
        }
    }

    fun reconcileServiceState() {
        val running = isServiceRunning(application, TProxyService::class.java)
        val connected = running && prefs.enable
        val preparation = _connectionPreparation.value
        setServiceEnabled(connected)
        if (running && !connected)
            _connectionPreparation.value = preparation ?: "Подготовка подключения…"
        setControlMenuClickable(!running || connected)
    }

    suspend fun updateCoreStats(): TrafficState? {
        if (!_isServiceEnabled.value) return null
        if (coreStatsClient == null)
            coreStatsClient = CoreStatsClient.create(prefs.apiAddress, prefs.apiPort)

        val client = coreStatsClient ?: return null
        val stats = client.getSystemStats()
        val trafficStatsEnabled = prefs.activeTrafficStatsEnabled
        val traffic = if (trafficStatsEnabled) client.getTraffic() else null
        if (!_isServiceEnabled.value || coreStatsClient !== client) return null

        if (stats == null && traffic == null) {
            coreStatsClient?.close()
            coreStatsClient = null
            return null
        }

        val previous = _coreStatsState.value
        _coreStatsState.value =
            CoreStatsState(
                uplink = traffic?.uplink ?: previous.uplink,
                downlink = traffic?.downlink ?: previous.downlink,
                numGoroutine = stats?.numGoroutine ?: previous.numGoroutine,
                numGC = stats?.numGC ?: previous.numGC,
                alloc = stats?.alloc ?: previous.alloc,
                totalAlloc = stats?.totalAlloc ?: previous.totalAlloc,
                sys = stats?.sys ?: previous.sys,
                mallocs = stats?.mallocs ?: previous.mallocs,
                frees = stats?.frees ?: previous.frees,
                liveObjects = stats?.liveObjects ?: previous.liveObjects,
                pauseTotalNs = stats?.pauseTotalNs ?: previous.pauseTotalNs,
                uptime = stats?.uptime ?: previous.uptime,
                trafficStatsEnabled = trafficStatsEnabled,
            )
        Log.d(TAG, "Core stats updated")
        return traffic
    }

    fun startTProxyService(action: String) {
        scope.launch {
            if (selectedConfigFile() == null) {
                sendEvent(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.not_select_config))
                )
                Log.w(TAG, "Cannot start service: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            _connectionPreparation.value = "Подготовка подключения…"
            val intent = Intent(application, TProxyService::class.java).setAction(action)
            sendEvent(MainViewUiEvent.StartService(intent))
        }
    }

    fun stopTProxyService() {
        _connectionPreparation.value = null
        setControlMenuClickable(false)
        scope.launch {
            val intent =
                Intent(
                        application,
                        TProxyService::class.java,
                    )
                    .setAction(TProxyService.ACTION_DISCONNECT)
            sendEvent(MainViewUiEvent.StartService(intent))
        }
    }

    fun prepareAndStartVpn(vpnPrepareLauncher: ActivityResultLauncher<Intent>) {
        scope.launch {
            if (selectedConfigFile() == null) {
                sendEvent(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.not_select_config))
                )
                Log.w(TAG, "Cannot prepare VPN: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            val vpnIntent = VpnService.prepare(application)
            if (vpnIntent != null) {
                vpnPrepareLauncher.launch(vpnIntent)
            } else {
                startTProxyService(TProxyService.ACTION_CONNECT)
            }
        }
    }

    fun registerTProxyServiceReceivers() {
        val application = application
        val startSuccessFilter = IntentFilter(TProxyService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(
                startReceiver,
                startSuccessFilter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            application.registerReceiver(startReceiver, startSuccessFilter)
        }

        val stopSuccessFilter = IntentFilter(TProxyService.ACTION_STOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(
                stopReceiver,
                stopSuccessFilter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            application.registerReceiver(stopReceiver, stopSuccessFilter)
        }
        val preparationFilter = IntentFilter(TProxyService.ACTION_PREPARATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(
                preparationReceiver,
                preparationFilter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            application.registerReceiver(preparationReceiver, preparationFilter)
        }
        Log.d(TAG, "TProxyService receivers registered.")
    }

    fun unregisterTProxyServiceReceivers() {
        val application = application
        application.unregisterReceiver(startReceiver)
        application.unregisterReceiver(stopReceiver)
        application.unregisterReceiver(preparationReceiver)
        Log.d(TAG, "TProxyService receivers unregistered.")
    }

    fun close() {
        coreStatsClient?.close()
    }

    companion object {
        @Suppress("DEPRECATION")
        fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return activityManager.getRunningServices(Int.MAX_VALUE).any { service ->
                serviceClass.name == service.service.className
            }
        }
    }
}
