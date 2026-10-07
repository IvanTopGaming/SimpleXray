package com.simplexray.an.service.runtime

import android.content.Context
import android.util.Log
import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.core.config.ConfigUtils
import com.simplexray.an.core.config.ConfigUtils.extractPortsFromJson
import com.simplexray.an.core.config.logging.LoggingConfigCompiler
import com.simplexray.an.core.config.profile.ProfileGeodata
import com.simplexray.an.core.files.config.ServerConfigLock
import com.simplexray.an.core.geodata.GeodataManager
import com.simplexray.an.core.runtime.assets.CoreAssetSnapshot
import com.simplexray.an.feature.routing.server.RoutingServerSources
import com.simplexray.an.feature.subscriptions.data.SubscriptionManager
import com.simplexray.an.feature.subscriptions.data.files.SubscriptionConfigFiles
import com.simplexray.an.prefs.Preferences
import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

internal class RuntimeConfigPreparer(private val context: Context) {
    private val filesDir
        get() = context.filesDir

    private val cacheDir
        get() = context.cacheDir

    suspend fun prepare(
        prefs: Preferences,
        geodataClient: () -> OkHttpClient,
        reportPreparation: (String) -> Unit,
        onSnapshotCreated: (CoreAssetSnapshot) -> Unit,
    ): PreparedRuntimeConfig {
        val builder = AppConfig(prefs)
        val vpn = VpnRuntimeSettings(prefs, builder)
        val (preset, pendingGeodata) = prefs.readRoutingPresetState()
        val routing = preset.routing
        val sourceFile = File(requireNotNull(prefs.selectedConfigPath))
        val (source, subscription, serverSources) =
            SubscriptionManager.configMutex.withLock {
                ServerConfigLock.withSnapshot(filesDir) {
                    val subscriptions = prefs.subscriptions
                    Triple(
                        sourceFile.readText(),
                        subscriptions.firstOrNull { sourceFile.name in it.files },
                        RoutingServerSources.read(routing, filesDir, subscriptions),
                    )
                }
            }
        val serverName =
            subscription?.let {
                SubscriptionConfigFiles.serverName(it, sourceFile.nameWithoutExtension)
            } ?: sourceFile.nameWithoutExtension.ifBlank { "Сервер" }
        val compiled = builder.buildProfile(source, routing, serverSources = serverSources)
        val trafficStatsEnabled =
            LoggingConfigCompiler.trafficStatsEnabled(compiled, builder.logSettings.trafficStats)
        val required = ProfileGeodata.requiredFiles(compiled)
        val urls = mapOf("geoip.dat" to preset.geoipUrl, "geosite.dat" to preset.geositeUrl)
        val refresh = pendingGeodata.filter { (name, url) -> urls[name] == url }.keys
        val manager = GeodataManager(filesDir, { urls.getValue(it) }, geodataClient())
        return manager.withAvailable(
            required,
            { reportPreparation(it) },
            refresh = refresh,
            onPublished = { name -> prefs.acknowledgeGeodata(name, urls.getValue(name)) },
        ) {
            val snapshot =
                CoreAssetSnapshot.create(filesDir, cacheDir, required).also(onSnapshotCreated)
            val port = requireNotNull(findAvailablePort(extractPortsFromJson(compiled)))
            val address = "127.${(0..255).random()}.${(0..255).random()}.${(1..254).random()}"
            val content =
                ConfigUtils.injectStatsService(
                    address,
                    port,
                    compiled,
                    trafficStatsEnabled = trafficStatsEnabled,
                    preserveTrafficPolicy = true,
                )
            PreparedRuntimeConfig(
                content,
                address,
                port,
                snapshot,
                vpn,
                trafficStatsEnabled,
                serverName,
                subscription?.url?.toHttpUrlOrNull()?.host,
            )
        }
    }

    private fun findAvailablePort(excludedPorts: Set<Int>): Int? {
        (10000..65535).shuffled().forEach { port ->
            if (port in excludedPorts) return@forEach
            runCatching {
                    ServerSocket(port).use { socket -> socket.reuseAddress = true }
                    port
                }
                .onFailure { Log.d(TAG, "Port $port unavailable: ${it.message}") }
                .onSuccess {
                    return port
                }
        }
        return null
    }

    private companion object {
        const val TAG = "VpnService"
    }
}
