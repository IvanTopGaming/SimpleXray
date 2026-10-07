package com.simplexray.an.core.network.probe

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

internal suspend fun probeTcp(config: String, timeoutMs: Int): Long {
    require(timeoutMs in 1..30000) { "Время ожидания должно быть от 1 до 30000 мс" }
    currentCoroutineContext().ensureActive()
    val endpoint = probeTcpEndpoint(config)
    return withTimeout(timeoutMs.toLong()) {
        suspendCancellableCoroutine { continuation ->
            val socket = Socket(Proxy.NO_PROXY)
            continuation.invokeOnCancellation { runCatching { socket.close() } }
            Dispatchers.IO.dispatch(
                continuation.context,
                Runnable {
                    socket.use {
                        try {
                            if (!continuation.isActive) return@Runnable
                            val started = System.nanoTime()
                            socket.connect(
                                InetSocketAddress(endpoint.host, endpoint.port),
                                timeoutMs,
                            )
                            if (continuation.isActive)
                                continuation.resume(
                                    ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                                )
                        } catch (exception: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        }
                    }
                },
            )
        }
    }
}
