package com.simplexray.an.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.simplexray.an.core.network.socks.LocalProxyEndpoint
import com.simplexray.an.core.network.socks.socksAuthenticationEnabled
import com.simplexray.an.core.runtime.assets.CoreAssetSnapshot
import com.simplexray.an.core.runtime.lifecycle.CoreLifecycleGate
import com.simplexray.an.core.runtime.process.CoreRuntimeProcess
import com.simplexray.an.core.runtime.stats.CoreStatsClient
import com.simplexray.an.feature.dashboard.model.TrafficState
import com.simplexray.an.feature.logs.data.LogFileManager
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.runtime.NotificationTrafficMonitor
import com.simplexray.an.service.runtime.PreparedRuntimeConfig
import com.simplexray.an.service.runtime.RuntimeConfigPreparer
import com.simplexray.an.service.runtime.ServiceLogBroadcaster
import com.simplexray.an.service.runtime.ServiceNotifications
import com.simplexray.an.service.runtime.VpnRuntimeSettings
import com.simplexray.an.service.runtime.VpnTunnelConfig
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class TProxyService : VpnService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logBroadcaster = ServiceLogBroadcaster(this)
    private val notifications = ServiceNotifications(this)
    private lateinit var logFileManager: LogFileManager

    @Volatile private var xrayProcess: Process? = null
    private var tunFd: ParcelFileDescriptor? = null

    private val lifecycleGate = CoreLifecycleGate()
    private var launchJob: Job? = null
    private var coreJob: Job? = null

    private var activeRuntime: VpnRuntimeSettings? = null

    private fun reportPreparation(message: String, error: Boolean = false) {
        sendBroadcast(
            Intent(ACTION_PREPARATION)
                .setPackage(packageName)
                .putExtra(EXTRA_PREPARATION, message)
                .putExtra(EXTRA_PREPARATION_ERROR, error)
        )
    }

    private fun geodataClient(): okhttp3.OkHttpClient {
        val builder = okhttp3.OkHttpClient.Builder()
        val active = activeRuntime
        if (active != null && xrayProcess?.isAlive == true) {
            builder.proxy(
                java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress(active.socksAddress, active.socksPort),
                )
            )
            java.net.Authenticator.setDefault(
                object : java.net.Authenticator() {
                    override fun getPasswordAuthentication(): java.net.PasswordAuthentication? =
                        if (
                            requestingHost == active.socksAddress &&
                                requestingPort == active.socksPort &&
                                requestingProtocol.equals("SOCKS5", ignoreCase = true) &&
                                socksAuthenticationEnabled(
                                    active.socksUsername,
                                    active.socksPassword,
                                )
                        )
                            java.net.PasswordAuthentication(
                                active.socksUsername,
                                active.socksPassword.toCharArray(),
                            )
                        else null
                }
            )
        }
        return builder.build()
    }

    override fun onCreate() {
        super.onCreate()
        logFileManager = LogFileManager(this)
        CoreAssetSnapshot.clearStale(cacheDir)
        Preferences(this).enable = false
        Log.d(TAG, "TProxyService created.")
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val action = intent.action
        when (action) {
            ACTION_DISCONNECT -> {
                stopXray()
                return START_NOT_STICKY
            }

            ACTION_RELOAD_CONFIG -> {
                if (xrayProcess != null) requestStart(reload = true)
                return START_STICKY
            }

            ACTION_START -> {
                requestStart()
                return START_STICKY
            }

            else -> {
                requestStart()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Preferences(this).enable = false
        logBroadcaster.flush()
        serviceScope.cancel()
        Log.d(TAG, "TProxyService destroyed.")
        exitProcess(0)
    }

    override fun onRevoke() {
        stopXray()
        super.onRevoke()
    }

    private fun requestStart(reload: Boolean = false) {
        if (
            launchJob?.isActive == true ||
                (!reload && (xrayProcess != null || coreJob?.isActive == true))
        )
            return
        val prefs = Preferences(this)
        if (!reload) logFileManager.clearLogs()
        val channel = if (prefs.disableVpn) "nosocks" else "socks5"
        notifications.createChannel(channel)
        notifications.show(channel)
        launchJob =
            serviceScope.launch {
                var snapshot: CoreAssetSnapshot? = null
                try {
                    reportPreparation("Подготовка подключения…")
                    val prepared =
                        RuntimeConfigPreparer(this@TProxyService)
                            .prepare(
                                prefs,
                                ::geodataClient,
                                { reportPreparation(it) },
                                { snapshot = it },
                            )
                    val vpn = prepared.vpn
                    currentCoroutineContext().ensureActive()
                    val generation = lifecycleGate.next() ?: return@launch
                    var oldProcess: Process? = null
                    if (
                        !lifecycleGate.runIfCurrent(generation) {
                            oldProcess = xrayProcess
                            xrayProcess = null
                        }
                    )
                        return@launch
                    oldProcess?.let {
                        it.destroy()
                        if (!it.waitFor(1, TimeUnit.SECONDS)) {
                            it.destroyForcibly()
                            it.waitFor(1, TimeUnit.SECONDS)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    reportPreparation("Подключение…")
                    lifecycleGate.runIfCurrent(generation) {
                        if (!vpn.disableVpn) startService(vpn) else closeTunnel()
                        if (serviceScope.isActive) {
                            activeRuntime = vpn
                            prefs.apiAddress = prepared.address
                            prefs.apiPort = prepared.port
                            coreJob = serviceScope.launch { runXrayProcess(prepared, generation) }
                            coreJob?.invokeOnCompletion { prepared.assets.close() }
                            snapshot = null
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message =
                        if (error is IllegalArgumentException)
                            error.message ?: "Проверь сервер и настройки DNS/роутинга"
                        else "Не удалось подготовить подключение. Проверь сеть и источники баз."
                    reportPreparation(message, error = true)
                    logFileManager.appendLog(message)
                    if (xrayProcess == null) stopXray()
                } finally {
                    snapshot?.close()
                }
            }
    }

    private fun runXrayProcess(prepared: PreparedRuntimeConfig, generation: Long) {
        var currentProcess: Process? = null
        val startupSucceeded = AtomicBoolean(false)
        try {
            Log.d(TAG, "Attempting to start xray process.")
            val libraryDir = getNativeLibraryDir(applicationContext)
            val prefs = Preferences(applicationContext)
            val xrayPath = "$libraryDir/libxray.so"

            val processBuilder =
                CoreRuntimeProcess.builder(File(xrayPath), prepared.assets.directory, filesDir)
            if (
                !lifecycleGate.runIfCurrent(generation) {
                    currentProcess = processBuilder.start()
                    this.xrayProcess = currentProcess
                }
            )
                return

            val launchedProcess = requireNotNull(currentProcess)
            launchedProcess.outputStream.use { os ->
                os.write(prepared.content.toByteArray())
                os.flush()
            }

            serviceScope.launch {
                CoreStatsClient.create(prepared.address, prepared.port).use { client ->
                    val ready =
                        withTimeoutOrNull(30_000) {
                            while (
                                isActive &&
                                    launchedProcess.isAlive &&
                                    xrayProcess === launchedProcess
                            ) {
                                if (client.getSystemStats() != null) return@withTimeoutOrNull true
                                delay(100)
                            }
                            false
                        } == true
                    lifecycleGate.runIfCurrent(generation) {
                        if (xrayProcess === launchedProcess && launchedProcess.isAlive) {
                            if (ready) {
                                startupSucceeded.set(true)
                                prefs.activeProxySettingsJson =
                                    LocalProxyEndpoint(
                                            prepared.vpn.socksAddress,
                                            prepared.vpn.socksPort,
                                            prepared.vpn.socksUsername,
                                            prepared.vpn.socksPassword,
                                        )
                                        .encode()
                                prefs.activeTrafficStatsEnabled = prepared.trafficStatsEnabled
                                notifications.setServerName(
                                    prepared.serverName,
                                    prepared.subscriptionDomain,
                                )
                                if (prepared.trafficStatsEnabled) {
                                    notifications.updateTraffic(
                                        TrafficState(0, 0),
                                        TrafficState(0, 0),
                                    )
                                } else {
                                    notifications.showStatisticsDisabled()
                                }
                                prefs.enable = true
                                sendBroadcast(
                                    Intent(ACTION_START).setPackage(application.packageName)
                                )
                            } else {
                                logFileManager.appendLog("Xray API startup timeout")
                                reportPreparation(
                                    "Xray не запустился за 30 секунд. Подробности в журнале.",
                                    error = true,
                                )
                                stopXray()
                            }
                        }
                    }
                    if (ready && prepared.trafficStatsEnabled) {
                        NotificationTrafficMonitor(this@TProxyService, notifications).run(
                            client,
                            { launchedProcess.isAlive && xrayProcess === launchedProcess },
                        ) { traffic, rates ->
                            lifecycleGate.runIfCurrent(generation) {
                                if (xrayProcess === launchedProcess && launchedProcess.isAlive) {
                                    notifications.updateTraffic(traffic, rates)
                                }
                            }
                        }
                    }
                }
            }

            val inputStream = launchedProcess.inputStream
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String
            Log.d(TAG, "Reading xray process output.")
            while ((reader.readLine().also { line = it }) != null) {
                logFileManager.appendLog(line)
                logBroadcaster.append(line)
            }
            Log.d(TAG, "xray process output stream finished.")
        } catch (e: InterruptedIOException) {
            Log.d(TAG, "Xray process reading interrupted.")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing xray", e)
            logFileManager.appendLog("Не удалось запустить Xray: $e")
        } finally {
            Log.d(TAG, "Xray process task finished.")
            currentProcess?.let {
                it.destroy()
                if (!it.waitFor(1, TimeUnit.SECONDS)) it.destroyForcibly()
            }
            lifecycleGate.runIfCurrent(generation) {
                Log.d(TAG, "Xray process exited unexpectedly or due to stop request. Stopping VPN.")
                if (!startupSucceeded.get()) {
                    reportPreparation(
                        "Не удалось запустить Xray. Подробности в журнале.",
                        error = true,
                    )
                }
                stopXray()
            }
        }
    }

    private fun stopXray() =
        lifecycleGate.stop {
            Log.d(TAG, "stopXray called with keepExecutorAlive=" + false)
            serviceScope.cancel()
            Log.d(TAG, "CoroutineScope cancelled.")

            xrayProcess?.destroy()
            xrayProcess = null
            Log.d(TAG, "xrayProcess reference nulled.")

            Log.d(TAG, "Calling stopService (stopping VPN).")
            stopService()
        }

    private fun closeTunnel() {
        if (tunFd != null) TProxyStopService()
        tunFd?.close()
        tunFd = null
    }

    private fun startService(prefs: VpnRuntimeSettings) {
        closeTunnel()
        val builder = VpnTunnelConfig.builder(this, prefs)
        tunFd = builder.establish()
        if (tunFd == null) {
            stopXray()
            return
        }
        val tproxyFile = File(cacheDir, "tproxy.conf")
        try {
            tproxyFile.createNewFile()
            FileOutputStream(tproxyFile, false).use { fos ->
                val tproxyConf = VpnTunnelConfig.configuration(prefs)
                fos.write(tproxyConf.toByteArray())
            }
        } catch (e: IOException) {
            Log.e(TAG, e.toString())
            stopXray()
            return
        }
        tunFd?.fd?.let { fd -> TProxyStartService(tproxyFile.absolutePath, fd) }
            ?: run {
                Log.e(TAG, "tunFd is null after establish()")
                stopXray()
                return
            }

        @Suppress("SameParameterValue") val channelName = "socks5"
        notifications.createChannel(channelName)
        notifications.show(channelName)
    }

    private fun stopService() {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        tunFd?.let {
            try {
                it.close()
            } catch (ignored: IOException) {} finally {
                tunFd = null
            }
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            TProxyStopService()
        }
        exit()
    }

    private fun exit() {
        Preferences(this).enable = false
        val stopIntent = Intent(ACTION_STOP)
        stopIntent.setPackage(application.packageName)
        sendBroadcast(stopIntent)
        stopSelf()
    }

    companion object {
        const val ACTION_CONNECT: String = "com.simplexray.an.CONNECT"
        const val ACTION_DISCONNECT: String = "com.simplexray.an.DISCONNECT"
        const val ACTION_START: String = "com.simplexray.an.START"
        const val ACTION_STOP: String = "com.simplexray.an.STOP"
        const val ACTION_PREPARATION: String = "com.simplexray.an.PREPARATION"
        const val EXTRA_PREPARATION: String = "preparation"
        const val EXTRA_PREPARATION_ERROR: String = "preparation_error"
        const val ACTION_LOG_UPDATE: String = "com.simplexray.an.LOG_UPDATE"
        const val ACTION_RELOAD_CONFIG: String = "com.simplexray.an.RELOAD_CONFIG"
        const val EXTRA_LOG_DATA: String = "log_data"
        private const val TAG = "VpnService"

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic @Suppress("FunctionName") private external fun TProxyStopService()

        @JvmStatic @Suppress("FunctionName") private external fun TProxyGetStats(): LongArray?

        fun getNativeLibraryDir(context: Context?): String? {
            if (context == null) {
                Log.e(TAG, "Context is null")
                return null
            }
            try {
                val applicationInfo = context.applicationInfo
                if (applicationInfo != null) {
                    val nativeLibraryDir = applicationInfo.nativeLibraryDir
                    Log.d(TAG, "Native Library Directory: $nativeLibraryDir")
                    return nativeLibraryDir
                } else {
                    Log.e(TAG, "ApplicationInfo is null")
                    return null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting native library dir", e)
                return null
            }
        }
    }
}
