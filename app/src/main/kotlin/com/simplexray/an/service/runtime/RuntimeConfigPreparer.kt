package com.simplexray.an.service.runtime

import android.content.Context
import android.util.Log
import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.core.config.ConfigUtils
import com.simplexray.an.core.config.ConfigUtils.extractPortsFromJson
import com.simplexray.an.core.config.logging.LoggingConfigCompiler
import com.simplexray.an.core.config.profile.ProfileGeodata
import com.simplexray.an.core.files.config.ServerConfigLock
import com.simplexray.an.core.geodata.GeodataFiles
import com.simplexray.an.core.geodata.GeodataManager
import com.simplexray.an.core.geodata.GeodataProgress
import com.simplexray.an.core.runtime.assets.CoreAssetSnapshot
import com.simplexray.an.feature.routing.server.RoutingServerSources
import com.simplexray.an.feature.subscriptions.data.SubscriptionManager
import com.simplexray.an.feature.subscriptions.data.files.SubscriptionConfigFiles
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.feature.subscriptions.routing.ResolvedSubscriptionRouting
import com.simplexray.an.feature.subscriptions.routing.SubscriptionRoutingResolver
import com.simplexray.an.feature.subscriptions.routing.SubscriptionRoutingStore
import com.simplexray.an.prefs.Preferences
import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.CancellationException
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
        onGeodataProgress: (GeodataProgress) -> Unit,
        onSnapshotCreated: (CoreAssetSnapshot) -> Unit,
    ): PreparedRuntimeConfig {
        val builder = AppConfig(prefs)
        val vpn = VpnRuntimeSettings(prefs, builder)
        val (preset, pendingGeodata) = prefs.readRoutingPresetState()
        val routing = preset.routing
        val sourceFile = File(requireNotNull(prefs.selectedConfigPath))
        val (source, subscription, serverSources, subscriptionRouting) =
            SubscriptionManager.configMutex.withLock {
                ServerConfigLock.withSnapshot(filesDir) {
                    val subscriptions = prefs.subscriptions
                    Sources(
                        sourceFile.readText(),
                        subscriptions.firstOrNull { sourceFile.name in it.files },
                        RoutingServerSources.read(routing, filesDir, subscriptions),
                        SubscriptionRoutingResolver.forSource(
                            filesDir,
                            sourceFile.name,
                            subscriptions,
                            preset,
                        ),
                    )
                }
            }
        val serverName =
            subscription?.let {
                SubscriptionConfigFiles.serverName(it, sourceFile.nameWithoutExtension)
            } ?: sourceFile.nameWithoutExtension.ifBlank { "Сервер" }
        val compiled =
            builder.buildProfile(
                source,
                routing,
                serverSources = serverSources + subscriptionRouting?.serverSources.orEmpty(),
                layers = listOfNotNull(subscriptionRouting?.layer),
            )
        val trafficStatsEnabled =
            LoggingConfigCompiler.trafficStatsEnabled(compiled, builder.logSettings.trafficStats)
        val required = ProfileGeodata.requiredFiles(compiled)
        val subscriptionGeodata = subscriptionRouting?.geodata.orEmpty()
        val urls =
            mapOf(
                GeodataFiles.GEOIP to preset.geoipUrl,
                GeodataFiles.GEOSITE to preset.geositeUrl,
            ) + subscriptionGeodata
        val refresh = pendingGeodata.filter { (name, url) -> urls[name] == url }.keys
        val manager = GeodataManager(filesDir, { urls.getValue(it) }, geodataClient())
        val routingStore = SubscriptionRoutingStore(filesDir)
        subscriptionGeodata
            .filter { (name, url) ->
                name in required &&
                    File(filesDir, name).isFile &&
                    routingStore.geodataSource(name) != url
            }
            .forEach { (name, url) ->
                try {
                    manager.update(name, onGeodataProgress)
                    routingStore.markGeodata(name, url)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Subscription geodata refresh failed: ${error.javaClass.simpleName}")
                }
            }
        return manager.withAvailable(
            required,
            onGeodataProgress,
            refresh = refresh,
            onPublished = { name ->
                if (GeodataFiles.isSubscription(name))
                    routingStore.markGeodata(name, urls.getValue(name))
                else prefs.acknowledgeGeodata(name, urls.getValue(name))
            },
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

    private data class Sources(
        val source: String,
        val subscription: Subscription?,
        val serverSources: Map<String, String>,
        val subscriptionRouting: ResolvedSubscriptionRouting?,
    )

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
