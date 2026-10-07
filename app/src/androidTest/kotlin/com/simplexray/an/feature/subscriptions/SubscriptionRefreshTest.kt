package com.simplexray.an.feature.subscriptions

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.simplexray.an.R
import com.simplexray.an.feature.subscriptions.data.SubscriptionManager
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.prefs.Preferences
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SubscriptionRefreshTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val prefs = Preferences(app)
    private val manager = SubscriptionManager(app, prefs) { false }
    private val oldSubs = prefs.subscriptions
    private val oldOrder = prefs.configFilesOrder
    private val oldSelection = prefs.selectedConfigPath
    private val oldAutoUpdate = prefs.autoUpdateSubscriptions
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val body =
        "vless://11111111-1111-1111-1111-111111111111@example.invalid:443?security=tls&type=tcp#NL"
    private val reply =
        AtomicReference(
            "HTTP/1.1 200 OK\r\nSubscription-Userinfo: upload=100; download=300; total=1000; expire=2000000000\r\nProfile-Title: Provider\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
        )
    private var blockResponse: CountDownLatch? = null
    private val requested = CountDownLatch(1)
    private lateinit var subscription: Subscription

    @Before
    fun setup() = runBlocking {
        prefs.subscriptions = emptyList()
        thread(isDaemon = true, name = "subscription-http-fixture") {
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        requested.countDown()
                        blockResponse?.await(10, TimeUnit.SECONDS)
                        val response =
                            if (request.contains("/bad "))
                                "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            else reply.get()
                        socket.getOutputStream().write(response.toByteArray())
                    }
                }
            }
        }
        subscription = manager.create("refresh-fixture", "http://127.0.0.1:${server.localPort}/sub")
    }

    @After
    fun cleanup() {
        blockResponse?.countDown()
        server.close()
        app.filesDir
            .listFiles()
            ?.filter { it.name.startsWith("refresh-fixture - ") }
            ?.forEach { it.delete() }
        prefs.subscriptions = oldSubs
        prefs.configFilesOrder = oldOrder
        prefs.selectedConfigPath = oldSelection
        prefs.autoUpdateSubscriptions = oldAutoUpdate
    }

    @Test
    fun responseHeadersReachPersistedSubscription() = runBlocking {
        val updated = manager.refresh(subscription.id).getOrThrow()
        val json = Gson().toJsonTree(prefs.subscriptions.single()).asJsonObject
        assertTrue("HTTP quota metadata must be persisted", json.has("usage"))
        assertEquals(1000L, json.getAsJsonObject("usage").get("total").asLong)
        assertEquals("Provider", updated.displayName)
        assertTrue(updated.files.isNotEmpty())
        assertTrue(File(app.filesDir, updated.files.single()).exists())
    }

    @Test
    fun failedRefreshPreservesWorkingFilesAndMetadata() = runBlocking {
        val good = manager.refresh(subscription.id).getOrThrow()
        val file = File(app.filesDir, good.files.single())
        val contents = file.readText()
        prefs.selectedConfigPath = file.absolutePath
        reply.set("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        assertTrue(manager.refresh(subscription.id).isFailure)
        val stored = prefs.subscriptions.single()
        assertEquals(good.lastUpdated, stored.lastUpdated)
        assertEquals(contents, file.readText())
        assertEquals(file.absolutePath, prefs.selectedConfigPath)
        assertTrue(
            "Background failures must remain visible",
            Gson().toJsonTree(stored).asJsonObject.has("lastError"),
        )
    }

    @Test
    fun invalidJsonRefreshPreservesWorkingServersAndSelection() = runBlocking {
        val good = manager.refresh(subscription.id).getOrThrow()
        val file = File(app.filesDir, good.files.single())
        val contents = file.readText()
        prefs.selectedConfigPath = file.absolutePath
        val order = prefs.configFilesOrder
        val invalidBodies =
            listOf(
                "{broken",
                """{"outbounds":[{"protocol":"unsupported"}]}""",
                """{"outbounds":[{"protocol":"trojan","proxySettings":{"tag":"missing"},"settings":{"servers":[{"address":"example.invalid","port":443,"password":"secret"}]}}]}""",
            )
        for (invalid in invalidBodies) {
            reply.set(
                "HTTP/1.1 200 OK\r\nContent-Length: ${invalid.toByteArray().size}\r\nConnection: close\r\n\r\n$invalid"
            )
            assertTrue(manager.refresh(subscription.id).isFailure)
            val stored = prefs.subscriptions.single()
            assertEquals(good.files, stored.files)
            assertEquals(good.lastUpdated, stored.lastUpdated)
            assertEquals(good.usage, stored.usage)
            assertEquals(good.displayTitle, stored.displayTitle)
            assertEquals(contents, file.readText())
            assertEquals(file.absolutePath, prefs.selectedConfigPath)
            assertEquals(order, prefs.configFilesOrder)
            assertEquals(app.getString(R.string.invalid_config_format), stored.lastError)
        }
    }

    @Test
    fun missingHeadersClearPreviousMetadata() = runBlocking {
        manager.refresh(subscription.id).getOrThrow()
        reply.set(
            "HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
        )
        manager.refresh(subscription.id).getOrThrow()
        val json = Gson().toJsonTree(prefs.subscriptions.single()).asJsonObject
        assertFalse(json.has("usage"))
        assertEquals("127.0.0.1", prefs.subscriptions.single().displayName)
    }

    @Test
    fun cancellationDoesNotCommitAResponse() = runBlocking {
        blockResponse = CountDownLatch(1)
        val refresh = launch(Dispatchers.IO) { manager.refresh(subscription.id) }
        assertTrue(requested.await(10, TimeUnit.SECONDS))
        refresh.cancel()
        blockResponse!!.countDown()
        refresh.join()
        assertEquals(0L, prefs.subscriptions.single().lastUpdated)
        assertTrue(prefs.subscriptions.single().files.isEmpty())
    }

    @Test
    fun backgroundRefreshContinuesAfterOneProviderFails() = runBlocking {
        prefs.autoUpdateSubscriptions = true
        manager.updateUrl(subscription.id, "http://127.0.0.1:${server.localPort}/bad")
        val second = manager.create("refresh-fixture", "http://127.0.0.1:${server.localPort}/good")
        assertTrue("Failed provider requests retry", manager.refreshAll())
        assertTrue(prefs.subscriptions.first { it.id == subscription.id }.files.isEmpty())
        assertTrue(prefs.subscriptions.first { it.id == second.id }.files.isNotEmpty())
    }

    @Test
    fun disabledBackgroundRefreshDoesNotFetch() = runBlocking {
        prefs.autoUpdateSubscriptions = false
        assertFalse(manager.refreshAll())
        assertEquals(1L, requested.count)
        assertEquals(0L, prefs.subscriptions.single().lastUpdated)
    }

    @Test
    fun newUrlClearsOldProviderMetadataButKeepsServers() = runBlocking {
        val first = manager.refresh(subscription.id).getOrThrow()
        assertTrue(manager.updateUrl(subscription.id, "https://new.example.invalid/sub"))
        val updated = prefs.subscriptions.single()
        assertNull(updated.usage)
        assertNull(updated.lastError)
        assertEquals(0L, updated.lastUpdated)
        assertEquals("new.example.invalid", updated.displayName)
        assertEquals(first.files, updated.files)
    }
}
