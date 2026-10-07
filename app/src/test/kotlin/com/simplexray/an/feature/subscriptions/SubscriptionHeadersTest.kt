package com.simplexray.an.feature.subscriptions

import com.simplexray.an.feature.subscriptions.data.SubscriptionHeader
import com.simplexray.an.feature.subscriptions.data.subscriptionHeadersRequest
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class SubscriptionHeadersTest {
    @Test
    fun schemeHostAndPortChangesStripAllCustomHeadersAndCredentials() {
        val origin = "https://provider.example/feed".toHttpUrl()
        val headers =
            listOf(
                SubscriptionHeader("X-Provider-Key", "secret"),
                SubscriptionHeader("User-Agent", "private-agent"),
            )
        listOf(
                "http://provider.example/feed",
                "https://other.example/feed",
                "https://provider.example:444/feed",
            )
            .forEach { target ->
                val request =
                    Request.Builder()
                        .url(target)
                        .header("Authorization", "secret")
                        .header("Cookie", "secret")
                        .header("X-Provider-Key", "secret")
                        .header("User-Agent", "private-agent")
                        .build()
                val filtered = subscriptionHeadersRequest(origin, request, headers)
                listOf("Authorization", "Cookie", "X-Provider-Key", "User-Agent").forEach {
                    assertNull(filtered.header(it))
                }
            }
    }

    @Test
    fun redirectsKeepHeadersOnSameOriginAndStripThemOnDifferentPort() {
        val first = ServerSocket(0)
        val second = ServerSocket(0)
        val executor = Executors.newFixedThreadPool(2)
        val origin = "http://127.0.0.1:${first.localPort}/feed".toHttpUrl()
        fun respond(server: ServerSocket, locations: List<String?>) =
            executor.submit<List<Map<String, String>>> {
                locations.map { location ->
                    server.soTimeout = 5000
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        reader.readLine()
                        val captured = generateSequence {
                            reader.readLine()
                        }
                            .takeWhile { it.isNotEmpty() }
                            .associate {
                                it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
                            }
                        socket.getOutputStream().bufferedWriter().apply {
                            write(
                                if (location == null) "HTTP/1.1 200 OK\r\n"
                                else "HTTP/1.1 302 Found\r\nLocation: $location\r\n"
                            )
                            write("Content-Length: 0\r\nConnection: close\r\n\r\n")
                            flush()
                        }
                        captured
                    }
                }
            }
        val headers =
            listOf(
                SubscriptionHeader("Authorization", "Bearer secret"),
                SubscriptionHeader("Cookie", "private=1"),
                SubscriptionHeader("X-Provider-Key", "private"),
                SubscriptionHeader("User-Agent", "provider-agent"),
            )
        val client =
            OkHttpClient.Builder()
                .addNetworkInterceptor { chain ->
                    chain.proceed(subscriptionHeadersRequest(origin, chain.request(), headers))
                }
                .build()
        try {
            val original =
                respond(first, listOf("/next", "http://127.0.0.1:${second.localPort}/feed"))
            val redirected = respond(second, listOf(null))
            client.newCall(Request.Builder().url(origin).build()).execute().use {
                assertEquals(200, it.code)
            }
            original.get(5, TimeUnit.SECONDS).forEach { request ->
                headers.forEach { assertEquals(it.value, request[it.name.lowercase()]) }
            }
            redirected.get(5, TimeUnit.SECONDS).forEach { request ->
                headers.forEach { assertNull(it.name, request[it.name.lowercase()]) }
            }
        } finally {
            first.close()
            second.close()
            executor.shutdownNow()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
