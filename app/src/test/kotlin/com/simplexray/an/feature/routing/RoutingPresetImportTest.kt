package com.simplexray.an.feature.routing

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlock
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.support.HostCoreVersionRead
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostCoreVersionRead::class])
class RoutingPresetImportTest {
    private fun preset(): RoutingPreset {
        val blocks =
            listOf(
                RoutingBlock(RouteTarget.BLOCK, "suffix:ads.example.com"),
                RoutingBlock(RouteTarget.DIRECT, "geoip:ru"),
                RoutingBlock(RouteTarget.PROXY, "geosite:google"),
            )
        return RoutingPreset(
            RoutingSettings(
                blocks = blocks,
                rules = blocks.flatMap(RoutingBlocks::parse),
                bypassLan = false,
            ),
            "https://rules.example/geoip.dat",
            "https://rules.example/geosite.dat",
        )
    }

    private fun fixture(block: suspend (MainViewModel, Application) -> Unit) = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val model = MainViewModel(app)
        val store = ViewModelStore().apply { put("main", model) }
        try {
            block(model, app)
        } finally {
            store.clear()
        }
    }

    @Test
    fun clipboardImportStagesThenAtomicallyReplacesWithoutTouchingServers() =
        fixture { model, app ->
            val before = model.prefs.readRoutingPreset()
            assertTrue(model.importRoutingPreset(preset().encodeLink()))
            assertEquals(preset(), model.pendingRoutingPreset.value)
            assertEquals(before, model.prefs.readRoutingPreset())
            assertTrue(model.applyRoutingPreset())
            assertEquals(preset(), model.prefs.readRoutingPreset())
            assertEquals(preset().routing, model.routingEditor.state.value.draft)
            assertTrue(model.routingEditor.state.value.revision > 0)
            assertNull(model.pendingRoutingPreset.value)
            assertTrue(model.prefs.subscriptions.isEmpty())
            assertTrue(app.filesDir.listFiles().orEmpty().none { it.name.endsWith(".json") })
        }

    @Test
    fun cancelAndInvalidClipboardContentPreserveCurrentPreset() = fixture { model, app ->
        val before = model.prefs.readRoutingPreset()
        assertTrue(model.importRoutingPreset(preset().encodeLink()))
        model.cancelRoutingPresetImport()
        assertEquals(before, model.prefs.readRoutingPreset())
        assertFalse(
            model.importRoutingPreset(
                preset().encode().replace("https://rules.example/geoip.dat", "file:///private")
            )
        )
        assertNull(model.pendingRoutingPreset.value)
        assertEquals(before, model.prefs.readRoutingPreset())
    }

    @Test
    fun exportedLinkContainsSavedRulesAndBothSourceUrls() = fixture { model, app ->
        model.prefs.applyRoutingPreset(preset())
        val link = requireNotNull(model.exportRoutingPreset())
        assertTrue(link.startsWith("simplexray://routing/"))
        assertEquals(preset(), RoutingPreset.detect(link))
    }

    @Test
    fun serverUrlRoutesToPresetWithoutCreatingServerOrSubscription() = fixture { model, app ->
        val before = model.prefs.readRoutingPreset()
        HttpFixture(preset().encode()).use { server ->
            assertTrue(model.importServer(server.url))
            assertEquals(1, server.requests.get())
            assertEquals(preset(), model.pendingRoutingPreset.value)
            assertEquals(before, model.prefs.readRoutingPreset())
            assertTrue(model.prefs.subscriptions.isEmpty())
            assertTrue(app.filesDir.listFiles().orEmpty().none { it.name.endsWith(".json") })
        }
    }

    @Test
    fun exportedLinkImportsThroughServersAndSubscriptionsWithoutRegistryChanges() =
        fixture { model, _ ->
            val before = model.prefs.readRoutingPreset()
            val link = preset().encodeLink()
            assertTrue(model.importServer(link))
            assertEquals(preset(), model.pendingRoutingPreset.value)
            model.cancelRoutingPresetImport()
            assertTrue(model.importSubscription("Rules", link))
            assertEquals(preset(), model.pendingRoutingPreset.value)
            assertEquals(before, model.prefs.readRoutingPreset())
            assertTrue(model.prefs.subscriptions.isEmpty())
        }

    @Test
    fun malformedPresetLinkCannotCreateSubscription() = fixture { model, _ ->
        assertFalse(model.importSubscription("Rules", "simplexray://routing/broken"))
        assertTrue(model.prefs.subscriptions.isEmpty())
        assertNull(model.pendingRoutingPreset.value)
    }

    @Test
    fun subscriptionUrlRecognizesPresetBeforeRegistryCreation() = fixture { model, app ->
        HttpFixture(preset().encode()).use { server ->
            assertTrue(model.importSubscription("Rules", server.url))
            assertEquals(1, server.requests.get())
            assertEquals(preset(), model.pendingRoutingPreset.value)
            assertTrue(model.prefs.subscriptions.isEmpty())
            assertTrue(app.filesDir.listFiles().orEmpty().none { it.name.endsWith(".json") })
        }
    }

    @Test
    fun normalSubscriptionReusesOneFetchedResponseAndKeepsItsMetadata() = fixture { model, _ ->
        val content =
            """{"outbounds":[{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":12345}]}}]}"""
        HttpFixture(content).use { server ->
            assertTrue(model.importSubscription("Servers", server.url))
            assertEquals(1, server.requests.get())
            val saved = model.prefs.subscriptions.single()
            assertEquals("Fixture", saved.displayTitle)
            assertEquals(1, saved.files.size)
            assertNull(model.pendingRoutingPreset.value)
        }
    }

    @Test
    fun routingUrlStagesPresetWithoutApplyingIt() = fixture { model, _ ->
        val before = model.prefs.readRoutingPreset()
        HttpFixture(preset().encode()).use { server ->
            assertTrue(model.importRoutingPreset(server.url))
            assertEquals(1, server.requests.get())
            assertEquals(preset(), model.pendingRoutingPreset.value)
            assertEquals(before, model.prefs.readRoutingPreset())
        }
    }

    @Test
    fun failedSubscriptionKeepsRetryEntryWithoutFetchingTwice() = fixture { model, _ ->
        HttpFixture("Unavailable", status = "503 Service Unavailable").use { server ->
            assertTrue(model.importSubscription("Servers", server.url))
            assertEquals(1, server.requests.get())
            assertEquals(server.url, model.prefs.subscriptions.single().url)
            assertTrue(model.prefs.subscriptions.single().files.isEmpty())
            assertNull(model.pendingRoutingPreset.value)
        }
    }

    @Test
    fun malformedIdentifiedPresetNeverBecomesAFailedSubscription() = fixture { model, _ ->
        HttpFixture(preset().encode().replace("https://rules.example/geosite.dat", "invalid"))
            .use { server ->
                assertFalse(model.importSubscription("Rules", server.url))
                assertTrue(model.prefs.subscriptions.isEmpty())
                assertNull(model.pendingRoutingPreset.value)
            }
    }

    private class HttpFixture(private val body: String, private val status: String = "200 OK") :
        Closeable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val url = "http://127.0.0.1:${socket.localPort}/document"
        private val worker =
            thread(isDaemon = true) {
                while (!socket.isClosed) runCatching {
                    socket.accept().use { client ->
                        client.soTimeout = 5000
                        val reader = client.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        requests.incrementAndGet()
                        val bytes = body.toByteArray()
                        client
                            .getOutputStream()
                            .write(
                                ("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nProfile-Title: Fixture\r\nConnection: close\r\n\r\n")
                                    .toByteArray()
                            )
                        client.getOutputStream().write(bytes)
                    }
                }
            }

        override fun close() {
            socket.close()
            worker.join(1000)
        }
    }
}
