package com.simplexray.an.feature.subscriptions

import android.app.Application
import com.simplexray.an.feature.subscriptions.data.SubscriptionManager
import com.simplexray.an.feature.subscriptions.routing.SubscriptionRoutingStore
import com.simplexray.an.prefs.Preferences
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SubscriptionRoutingRefreshTest {
    private val application = RuntimeEnvironment.getApplication()
    private val prefs = Preferences(application)
    private val manager = SubscriptionManager(application, prefs) { false }
    private val store = SubscriptionRoutingStore(application.filesDir)

    private fun link(text: String) =
        "simplexray://routing/" +
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    """{"format":"simplexray-routing","version":2,"routing":{"blocks":[{"target":"DIRECT","text":"$text"}]}}"""
                        .toByteArray()
                )

    private val servers = "trojan://secret@one.example:443?security=tls&sni=one.example#DE"

    private fun response(body: String = servers, vararg routing: String) =
        Result.success(SubscriptionManager.Response(body, "Feed", null, routing.toList()))

    private fun state(id: String) = prefs.subscriptions.single { it.id == id }

    @Test
    fun refreshStoresKeepsAndClearsSubscriptionRouting() = runBlocking {
        val sub = manager.create("Feed", "https://sub.example/token")
        assertTrue(manager.refresh(sub.id, response(routing = arrayOf("happ://routing/onadd/x\n" + link("suffix:a.example")))).isSuccess)
        assertEquals("suffix:a.example", store.read(sub.id)?.blocks?.single()?.text)
        assertNull(state(sub.id).routingError)

        assertTrue(manager.refresh(sub.id, response(routing = arrayOf("simplexray://routing/broken"))).isSuccess)
        assertEquals(1, state(sub.id).files.size)
        assertNotNull(state(sub.id).routingError)
        assertEquals("suffix:a.example", store.read(sub.id)?.blocks?.single()?.text)

        assertTrue(manager.refresh(sub.id, response("$servers\n${link("suffix:b.example")}")).isSuccess)
        assertEquals("suffix:b.example", store.read(sub.id)?.blocks?.single()?.text)
        assertNull(state(sub.id).routingError)

        assertTrue(manager.setRoutingDisabled(sub.id, true))
        assertTrue(state(sub.id).routingDisabled)
        assertTrue(manager.refresh(sub.id, response()).isSuccess)
        assertTrue(state(sub.id).routingDisabled)
        assertNull(store.read(sub.id))
    }

    @Test
    fun deletingSubscriptionRemovesItsRoutingAndDatabases() = runBlocking {
        val sub = manager.create("Feed", "https://sub.example/other")
        assertTrue(manager.refresh(sub.id, response(routing = arrayOf(link("suffix:a.example")))).isSuccess)
        val database = File(application.filesDir, "sub-${sub.id}-geosite.dat").apply { writeText("x") }
        store.markGeodata(database.name, "https://provider.example/geosite.dat")
        assertTrue(manager.delete(sub.id))
        assertNull(store.read(sub.id))
        assertFalse(database.exists())
        assertNull(store.geodataSource(database.name))
    }
}
