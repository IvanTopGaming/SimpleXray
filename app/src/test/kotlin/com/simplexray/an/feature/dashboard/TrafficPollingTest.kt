package com.simplexray.an.feature.dashboard

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.core.runtime.stats.CoreStatsClient
import com.simplexray.an.feature.dashboard.model.TrafficState
import com.simplexray.an.support.HostCoreVersionRead
import com.xray.app.stats.command.*
import io.grpc.InsecureServerCredentials
import io.grpc.Status
import io.grpc.okhttp.OkHttpServerBuilder
import io.grpc.stub.StreamObserver
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostCoreVersionRead::class])
class TrafficPollingTest {
    private class Stats : StatsServiceGrpc.StatsServiceImplBase() {
        @Volatile var traffic = TrafficState(1000, 2000)
        @Volatile var failTraffic = false
        @Volatile var failSystem = false
        @Volatile var omitDownlink = false
        @Volatile var httpTraffic: TrafficState? = null
        @Volatile var trafficRequests = 0

        override fun getSysStats(
            request: SysStatsRequest,
            response: StreamObserver<SysStatsResponse>,
        ) {
            if (failSystem) response.onError(Status.UNAVAILABLE.asRuntimeException())
            else {
                response.onNext(SysStatsResponse.newBuilder().setUptime(123).build())
                response.onCompleted()
            }
        }

        override fun queryStats(
            request: QueryStatsRequest,
            response: StreamObserver<QueryStatsResponse>,
        ) {
            trafficRequests++
            if (failTraffic) {
                response.onError(Status.UNAVAILABLE.asRuntimeException())
                return
            }
            val values =
                mapOf(
                    "inbound>>>__sx_client>>>traffic>>>uplink" to traffic.uplink,
                    "inbound>>>__sx_client>>>traffic>>>downlink" to traffic.downlink,
                    "inbound>>>__sx_client_extra>>>traffic>>>uplink" to 900000L,
                    "inbound>>>api>>>traffic>>>downlink" to 900000L,
                    "outbound>>>proxy>>>traffic>>>uplink" to traffic.uplink,
                    "outbound>>>proxy>>>traffic>>>downlink" to traffic.downlink,
                    "outbound>>>__sx_chain_1>>>traffic>>>uplink" to traffic.uplink,
                    "outbound>>>__sx_chain_1>>>traffic>>>downlink" to traffic.downlink,
                )
            val httpValues =
                httpTraffic
                    ?.let {
                        mapOf(
                            "inbound>>>__sx_http_client>>>traffic>>>uplink" to it.uplink,
                            "inbound>>>__sx_http_client>>>traffic>>>downlink" to it.downlink,
                            "inbound>>>__sx_http_client_extra>>>traffic>>>uplink" to 900000L,
                        )
                    }
                    .orEmpty()
            val result = QueryStatsResponse.newBuilder()
            (values + httpValues)
                .filterKeys {
                    it.contains(request.pattern) && !(omitDownlink && it.endsWith("downlink"))
                }
                .forEach { (name, value) ->
                    result.addStat(Stat.newBuilder().setName(name).setValue(value))
                }
            response.onNext(result.build())
            response.onCompleted()
            if (request.reset) traffic = TrafficState(0, 0)
        }
    }

    private fun fixture(block: suspend (Stats, MainViewModel, Int) -> Unit) = runBlocking {
        val stats = Stats()
        val server =
            OkHttpServerBuilder.forPort(0, InsecureServerCredentials.create())
                .addService(stats)
                .build()
                .start()
        val model = MainViewModel(RuntimeEnvironment.getApplication())
        val store = ViewModelStore().apply { put("main", model) }
        model.prefs.apiAddress = "127.0.0.1"
        model.prefs.apiPort = server.port
        model.setServiceEnabled(true)
        try {
            block(stats, model, server.port)
        } finally {
            store.clear()
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun readsOnlyClientInboundAndDoesNotResetCounters() = fixture { _, _, port ->
        CoreStatsClient.create("127.0.0.1", port).use { client ->
            assertEquals(TrafficState(1000, 2000), client.getTraffic())
            assertEquals(TrafficState(1000, 2000), client.getTraffic())
        }
    }

    @Test
    fun addsHttpClientTrafficOnceInOneRpc() = fixture { stats, _, port ->
        stats.httpTraffic = TrafficState(3000, 4000)
        CoreStatsClient.create("127.0.0.1", port).use { client ->
            assertEquals(TrafficState(4000, 6000), client.getTraffic())
            assertEquals(1, stats.trafficRequests)
        }
    }

    @Test
    fun disabledTrafficSkipsCounterRpcButKeepsSystemStats() = fixture { stats, model, _ ->
        model.prefs.activeTrafficStatsEnabled = false
        model.setServiceEnabled(true)
        assertNull(model.updateCoreStats())
        assertFalse(model.coreStatsState.value.trafficStatsEnabled)
        assertEquals(123, model.coreStatsState.value.uptime)
        assertEquals(0, stats.trafficRequests)
    }

    @Test
    fun partialTrafficFailurePreservesVolumeUntilNextSuccessfulSample() =
        fixture { stats, model, _ ->
            model.updateCoreStats()
            val before = model.coreStatsState.value
            assertTrue(before.uplink > 0 && before.downlink > 0)
            stats.failTraffic = true
            val missing: Any? = model.updateCoreStats()
            assertEquals(before.uplink, model.coreStatsState.value.uplink)
            assertEquals(before.downlink, model.coreStatsState.value.downlink)
            assertNull(missing)
            assertEquals(123, model.coreStatsState.value.uptime)
            stats.failTraffic = false
            stats.traffic = TrafficState(3000, 6000)
            assertEquals(TrafficState(3000, 6000), model.updateCoreStats())
            assertEquals(3000L, model.coreStatsState.value.uplink)
            assertEquals(6000L, model.coreStatsState.value.downlink)
        }

    @Test
    fun missingCountersAreNotReportedAsZeroVolume() = fixture { stats, _, port ->
        stats.omitDownlink = true
        CoreStatsClient.create("127.0.0.1", port).use { client -> assertNull(client.getTraffic()) }
    }

    @Test
    fun failedSystemPollDoesNotEraseItsLastValuesOrSuccessfulTraffic() =
        fixture { stats, model, _ ->
            model.updateCoreStats()
            stats.failSystem = true
            stats.traffic = TrafficState(4000, 8000)
            model.updateCoreStats()
            assertEquals(123, model.coreStatsState.value.uptime)
            assertEquals(4000L, model.coreStatsState.value.uplink)
        }

    @Test
    fun completeRpcFailurePreservesVolumeAndReconnectsOnNextPoll() = fixture { stats, model, _ ->
        model.updateCoreStats()
        val before = model.coreStatsState.value
        stats.failSystem = true
        stats.failTraffic = true
        val missing: Any? = model.updateCoreStats()
        assertEquals(before, model.coreStatsState.value)
        assertNull(missing)
        stats.failSystem = false
        stats.failTraffic = false
        stats.traffic = TrafficState(2000, 4000)
        assertEquals(TrafficState(2000, 4000), model.updateCoreStats())
    }
}
