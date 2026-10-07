package com.simplexray.an.feature.subscriptions

import android.app.Application
import android.os.Build
import androidx.preference.PreferenceManager
import com.simplexray.an.BuildConfig
import com.simplexray.an.feature.subscriptions.data.SubscriptionManager
import com.simplexray.an.prefs.Preferences
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SubscriptionClientIdentityRequestTest {
    private val application = RuntimeEnvironment.getApplication()
    private val prefs = Preferences(application)

    private fun storeIdentity(json: String) {
        PreferenceManager.getDefaultSharedPreferences(application)
            .edit()
            .putString("SubscriptionClientIdentity", json)
            .commit()
    }

    private fun manager() = SubscriptionManager(application, Preferences(application)) { false }

    @Test
    fun nonAsciiSystemMetadataCannotBreakSubscriptionRefresh() = runBlocking {
        val model = Build.MODEL
        val release = Build.VERSION.RELEASE
        try {
            ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Téléphone\r\n样品")
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "RELEASE", "15\r\n\u0000β")
            SubscriptionTestServer(null).use { server ->
                val subscription = manager().create("Default metadata", server.url)
                assertTrue(manager().refresh(subscription.id).isSuccess)
                val request = server.requests().single()
                assertEquals("Telephone", request["x-device-model"])
                assertEquals("15", request["x-ver-os"])
                assertNull(request["x-hwid"])
            }
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "MODEL", model)
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "RELEASE", release)
        }
    }

    @Test
    fun allSubscriptionsUseTheSavedProfileAndNextRefreshUsesEdits() = runBlocking {
        storeIdentity(
            """{"hwid":"custom-device-123","deviceModel":"Pixel 9","deviceOs":"Android","osVersion":"15","userAgent":"Example/2.0"}"""
        )
        prefs.subscriptionSendHwid = true
        PreferenceManager.getDefaultSharedPreferences(application)
            .edit()
            .putString(
                "SubscriptionHeaders",
                """[{"name":"User-Agent","value":"Legacy/1.0"},{"name":"Authorization","value":"Bearer obsolete"}]""",
            )
            .commit()
        SubscriptionTestServer(null, null, null).use { server ->
            val first = manager().create("First", server.url)
            val second = manager().create("Second", server.url)
            assertTrue(manager().refresh(first.id).isSuccess)
            assertTrue(manager().refresh(second.id).isSuccess)
            storeIdentity("""{"hwid":"changed-device-456","userAgent":"Example/3.0"}""")
            assertTrue(manager().refresh(first.id).isSuccess)
            val requests = server.requests()
            requests.take(2).forEach { request ->
                assertEquals("custom-device-123", request["x-hwid"])
                assertEquals("Pixel 9", request["x-device-model"])
                assertEquals("Android", request["x-device-os"])
                assertEquals("15", request["x-ver-os"])
                assertEquals("Example/2.0", request["user-agent"])
                assertNull(request["authorization"])
            }
            assertEquals("changed-device-456", requests.last()["x-hwid"])
            assertEquals("Example/3.0", requests.last()["user-agent"])
            assertEquals("Android", requests.last()["x-device-os"])
            assertEquals(Build.VERSION.RELEASE, requests.last()["x-ver-os"])
        }
    }

    @Test
    fun emptyProfileUsesDeviceDefaultsAndPreservesInstallationHwid() = runBlocking {
        prefs.subscriptionSendHwid = true
        val original = prefs.subscriptionInstallationId
        SubscriptionTestServer(null, null).use { server ->
            val subscription = manager().create("Existing", server.url)
            repeat(2) { assertTrue(manager().refresh(subscription.id).isSuccess) }
            server.requests().forEach { request ->
                assertEquals(original, request["x-hwid"])
                assertEquals(Build.MODEL, request["x-device-model"])
                assertEquals("Android", request["x-device-os"])
                assertEquals(Build.VERSION.RELEASE, request["x-ver-os"])
                assertEquals("SimpleXray/${BuildConfig.VERSION_NAME}", request["user-agent"])
            }
        }
    }

    @Test
    fun disablingHwidKeepsTheOtherOverridesAndDoesNotChangeStoredIdentifiers() = runBlocking {
        val original = prefs.subscriptionInstallationId
        storeIdentity("""{"hwid":"custom-device-123","deviceModel":"Chosen model"}""")
        prefs.subscriptionSendHwid = false
        SubscriptionTestServer(null, null, null).use { server ->
            val subscription = manager().create("First", server.url)
            assertTrue(manager().refresh(subscription.id).isSuccess)
            prefs.subscriptionSendHwid = true
            assertTrue(manager().refresh(subscription.id).isSuccess)
            storeIdentity("""{"deviceModel":"Chosen model"}""")
            assertTrue(manager().refresh(subscription.id).isSuccess)
            val requests = server.requests()
            assertNull(requests.first()["x-hwid"])
            assertEquals("Chosen model", requests.first()["x-device-model"])
            assertEquals("custom-device-123", requests[1]["x-hwid"])
            assertEquals(original, requests.last()["x-hwid"])
            assertEquals(original, prefs.subscriptionInstallationId)
        }
    }

    @Test
    fun redirectsKeepIdentityOnOriginalOriginAndStripItOnAnotherPort() = runBlocking {
        storeIdentity(
            """{"hwid":"custom-device-123","deviceModel":"Chosen model","deviceOs":"Chosen OS","osVersion":"42","userAgent":"Example/2.0"}"""
        )
        prefs.subscriptionSendHwid = true
        SubscriptionTestServer(null).use { other ->
            SubscriptionTestServer("/next", other.url).use { origin ->
                val subscription = manager().create("Redirected", origin.url)
                assertTrue(manager().refresh(subscription.id).isSuccess)
                origin.requests().forEach { request ->
                    assertEquals("custom-device-123", request["x-hwid"])
                    assertEquals("Chosen model", request["x-device-model"])
                    assertEquals("Chosen OS", request["x-device-os"])
                    assertEquals("42", request["x-ver-os"])
                    assertEquals("Example/2.0", request["user-agent"])
                }
                val redirected = other.requests().single()
                listOf("x-hwid", "x-device-model", "x-device-os", "x-ver-os", "user-agent")
                    .forEach { assertNull(it, redirected[it]) }
            }
        }
    }

    @Test
    fun invalidStoredIdentityFailsBeforeNetworkAccessWithoutDisclosingValues() = runBlocking {
        val subscription = manager().create("Invalid", "http://127.0.0.1:1/feed")
        listOf(
                "",
                "null",
                "[]",
                "{} trailing-secret",
                """{"unknown":"private-value"}""",
                """{"hwid":"short"}""",
                """{"hwid":"private-invalid-value!"}""",
                """{"hwid":"${"a".repeat(65)}"}""",
                """{"hwid":"valid-device-123","hwid":"another-device-123"}""",
                """{"deviceOs":null}""",
                """{"osVersion":15}""",
                """{"deviceModel":"Модель"}""",
                """{"deviceModel":"${"a".repeat(513)}"}""",
                """{"userAgent":"private\r\nInjected: value"}""",
            )
            .forEach { json ->
                storeIdentity(json)
                val result = manager().refresh(subscription.id)
                assertTrue(result.isFailure)
                val error = result.exceptionOrNull()?.message.orEmpty()
                assertTrue(error, error.contains("Данные клиента"))
                assertFalse(error.contains("private"))
                assertNull(result.exceptionOrNull()?.cause)
            }
    }
}

private class SubscriptionTestServer(vararg redirects: String?) : Closeable {
    private val server = ServerSocket(0)
    private val executor = Executors.newSingleThreadExecutor()
    val url = "http://127.0.0.1:${server.localPort}/feed"
    private val captured =
        executor.submit<List<Map<String, String>>> {
            redirects.map { redirect ->
                server.soTimeout = 10000
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    val lines = generateSequence {
                        reader.readLine()
                    }
                        .takeWhile { it.isNotEmpty() }
                        .toList()
                    val names = lines.map { it.substringBefore(':').lowercase() }
                    assertEquals("Duplicate HTTP headers", names.size, names.toSet().size)
                    val headers = lines.associate {
                        it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
                    }
                    val body =
                        """{"remarks":"Test","outbounds":[{"protocol":"trojan","settings":{"servers":[{"address":"test.example","port":443,"password":"test"}]}}]}"""
                    socket.getOutputStream().bufferedWriter().apply {
                        write(
                            if (redirect == null) "HTTP/1.1 200 OK\r\n"
                            else "HTTP/1.1 302 Found\r\nLocation: $redirect\r\n"
                        )
                        write(
                            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                        )
                        flush()
                    }
                    headers
                }
            }
        }

    fun requests(): List<Map<String, String>> = captured.get(10, TimeUnit.SECONDS)

    override fun close() {
        server.close()
        executor.shutdownNow()
    }
}
