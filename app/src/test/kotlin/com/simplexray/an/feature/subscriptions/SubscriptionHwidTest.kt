package com.simplexray.an.feature.subscriptions

import com.simplexray.an.feature.subscriptions.data.subscriptionHwidRequest
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class SubscriptionHwidTest {
    private val origin = "https://subscription.example/feed".toHttpUrl()

    @Test
    fun disabledHwidRemovesAnyExistingIdentifier() {
        val request = Request.Builder().url(origin).header("x-hwid", "old-id").build()
        assertNull(subscriptionHwidRequest(origin, request, null).header("x-hwid"))
    }

    @Test
    fun enabledHwidIsSentOnlyToOriginalOrigin() {
        listOf("https://subscription.example/feed", "https://subscription.example:443/next")
            .forEach { url ->
                val request = Request.Builder().url(url).build()
                assertEquals(
                    "installation-id",
                    subscriptionHwidRequest(origin, request, "installation-id").header("x-hwid"),
                )
            }
        listOf(
                "http://subscription.example/feed",
                "https://other.example/feed",
                "https://subscription.example:444/feed",
            )
            .forEach { url ->
                val request = Request.Builder().url(url).header("x-hwid", "installation-id").build()
                assertNull(
                    subscriptionHwidRequest(origin, request, "installation-id").header("x-hwid")
                )
            }
    }

    @Test
    fun redirectedRequestsNeverCarryHwidToAnotherOrigin() {
        val first = ServerSocket(0)
        val second = ServerSocket(0)
        val executor = Executors.newFixedThreadPool(2)
        fun respond(server: ServerSocket, location: String? = null): Future<String?> =
            executor.submit<String?> {
                server.soTimeout = 5000
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = generateSequence {
                        reader.readLine()
                    }
                        .takeWhile { it.isNotEmpty() }
                        .toList()
                    val writer = socket.getOutputStream().bufferedWriter()
                    writer.write(
                        if (location == null) "HTTP/1.1 200 OK\r\n"
                        else "HTTP/1.1 302 Found\r\nLocation: $location\r\n"
                    )
                    writer.write("Content-Length: 0\r\nConnection: close\r\n\r\n")
                    writer.flush()
                    headers
                        .firstOrNull { it.startsWith("x-hwid:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                }
            }
        try {
            val originalHwid = respond(first, "http://127.0.0.1:${second.localPort}/feed")
            val redirectedHwid = respond(second)
            val url = "http://127.0.0.1:${first.localPort}/feed".toHttpUrl()
            val client =
                OkHttpClient.Builder()
                    .addNetworkInterceptor { chain ->
                        chain.proceed(
                            subscriptionHwidRequest(url, chain.request(), "installation-id")
                        )
                    }
                    .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertEquals(200, response.code)
            }
            assertEquals("installation-id", originalHwid.get(5, TimeUnit.SECONDS))
            assertNull(redirectedHwid.get(5, TimeUnit.SECONDS))
        } finally {
            first.close()
            second.close()
            executor.shutdownNow()
        }
    }
}
