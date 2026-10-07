package com.simplexray.an.core.runtime.stats

import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.feature.dashboard.model.TrafficState
import com.xray.app.stats.command.QueryStatsRequest
import com.xray.app.stats.command.StatsServiceGrpc
import com.xray.app.stats.command.SysStatsRequest
import com.xray.app.stats.command.SysStatsResponse
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CoreStatsClient(private val channel: ManagedChannel) : Closeable {
    private val blockingStub: StatsServiceGrpc.StatsServiceBlockingStub =
        StatsServiceGrpc.newBlockingStub(channel)

    suspend fun getSystemStats(): SysStatsResponse? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = SysStatsRequest.newBuilder().build()
                blockingStub.withDeadlineAfter(2, TimeUnit.SECONDS).getSysStats(request)
            }
                .getOrNull()
        }

    suspend fun getTraffic(): TrafficState? =
        withContext(Dispatchers.IO) {
            val prefix = "inbound>>>${OwnedConfig.CLIENT_INBOUND_TAG}>>>traffic>>>"
            val request =
                QueryStatsRequest.newBuilder().setPattern("inbound>>>").setReset(false).build()

            runCatching { blockingStub.withDeadlineAfter(2, TimeUnit.SECONDS).queryStats(request) }
                .getOrNull()
                ?.statList
                ?.let { counters ->
                    val uplink =
                        counters.firstOrNull { it.name == prefix + "uplink" }?.value
                            ?: return@let null
                    val downlink =
                        counters.firstOrNull { it.name == prefix + "downlink" }?.value
                            ?: return@let null
                    val httpPrefix = "inbound>>>${OwnedConfig.HTTP_CLIENT_INBOUND_TAG}>>>traffic>>>"
                    val httpUplink =
                        counters.firstOrNull { it.name == httpPrefix + "uplink" }?.value
                    val httpDownlink =
                        counters.firstOrNull { it.name == httpPrefix + "downlink" }?.value
                    if ((httpUplink == null) != (httpDownlink == null)) return@let null
                    TrafficState(uplink + (httpUplink ?: 0), downlink + (httpDownlink ?: 0))
                }
        }

    override fun close() {
        channel.shutdownNow()
    }

    companion object {
        fun create(host: String, port: Int): CoreStatsClient {
            val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()
            return CoreStatsClient(channel)
        }
    }
}
