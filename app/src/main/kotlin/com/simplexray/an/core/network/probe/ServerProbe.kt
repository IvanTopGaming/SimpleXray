package com.simplexray.an.core.network.probe

import android.app.Application
import android.os.SystemClock
import com.simplexray.an.core.runtime.process.CoreRuntimeProcess
import com.simplexray.an.feature.servers.model.ProbeMethod
import com.simplexray.an.feature.servers.model.ServerCheckResult
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

class ServerProbe(private val application: Application) {
    suspend fun check(
        config: String,
        target: String,
        timeoutMs: Int,
        method: ProbeMethod = ProbeMethod.HTTP_GET,
    ): ServerCheckResult =
        withContext(Dispatchers.IO) {
            if (method == ProbeMethod.TCP)
                return@withContext try {
                    ServerCheckResult(latencyMs = probeTcp(config, timeoutMs))
                } catch (exception: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    ServerCheckResult(error = "Превышено время ожидания")
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: IllegalArgumentException) {
                    ServerCheckResult(
                        error = exception.message ?: "Некорректная конфигурация сервера"
                    )
                } catch (exception: Exception) {
                    currentCoroutineContext().ensureActive()
                    ServerCheckResult(error = "TCP-порт недоступен")
                }
            val url =
                target.toHttpUrlOrNull()
                    ?: return@withContext ServerCheckResult(error = "Некорректный адрес проверки")
            var process: Process? = null
            var client: OkHttpClient? = null
            try {
                val port =
                    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val username = UUID.randomUUID().toString()
                val password = UUID.randomUUID().toString()
                val isolated = buildProbeConfig(config, port, username, password)
                currentCoroutineContext().ensureActive()
                val builder =
                    CoreRuntimeProcess.builder(
                            File(application.applicationInfo.nativeLibraryDir, "libxray.so"),
                            application.filesDir,
                        )
                        .redirectOutput(File("/dev/null"))
                builder.environment()["XRAY_LOCATION_ASSET"] = application.filesDir.path
                val core = builder.start()
                process = core
                core.outputStream.use { it.write(isolated.toByteArray()) }
                withTimeout(5000) {
                    while (true) {
                        ensureActive()
                        if (!core.isAlive) throw IOException("Core exited")
                        val listening = runCatching {
                            Socket(Proxy.NO_PROXY).use {
                                it.connect(InetSocketAddress("127.0.0.1", port), 50)
                            }
                        }
                            .isSuccess
                        if (listening) break
                        delay(25)
                    }
                }
                val timeout = timeoutMs.coerceIn(1, 30000).toLong()
                val http =
                    OkHttpClient.Builder()
                        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                        .proxyAuthenticator { _, response ->
                            if (response.request.header("Proxy-Authorization") != null) null
                            else
                                response.request
                                    .newBuilder()
                                    .header(
                                        "Proxy-Authorization",
                                        Credentials.basic(username, password),
                                    )
                                    .build()
                        }
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .retryOnConnectionFailure(false)
                        .callTimeout(timeout, TimeUnit.MILLISECONDS)
                        .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                        .readTimeout(timeout, TimeUnit.MILLISECONDS)
                        .build()
                client = http
                val started = SystemClock.elapsedRealtime()
                val verb = if (method == ProbeMethod.HTTP_HEAD) "HEAD" else "GET"
                val (status, finished) =
                    if (url.isHttps)
                        http
                            .newCall(Request.Builder().url(url).method(verb, null).build())
                            .probeResponse()
                    else
                        withTimeout(timeout) {
                            probeHttp(
                                url,
                                port,
                                Credentials.basic(username, password),
                                timeout.toInt(),
                                method,
                            )
                        }
                if (status in 200..299)
                    ServerCheckResult(latencyMs = (finished - started).coerceAtLeast(1))
                else ServerCheckResult(error = "Ответ HTTP $status")
            } catch (exception: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                ServerCheckResult(error = "Превышено время ожидания")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                currentCoroutineContext().ensureActive()
                ServerCheckResult(error = "Не отвечает или ошибка конфигурации")
            } finally {
                client?.dispatcher?.cancelAll()
                client?.connectionPool?.evictAll()
                client?.dispatcher?.executorService?.shutdown()
                withContext(NonCancellable + Dispatchers.IO) {
                    process?.let {
                        it.destroy()
                        if (!it.waitFor(500, TimeUnit.MILLISECONDS)) {
                            it.destroyForcibly()
                            it.waitFor(500, TimeUnit.MILLISECONDS)
                        }
                        it.inputStream.close()
                        it.errorStream.close()
                    }
                }
            }
        }
}

private suspend fun probeHttp(
    url: HttpUrl,
    port: Int,
    credentials: String,
    timeout: Int,
    method: ProbeMethod,
): Pair<Int, Long> = suspendCancellableCoroutine { continuation ->
    Socket(Proxy.NO_PROXY).use { socket ->
        continuation.invokeOnCancellation { runCatching { socket.close() } }
        try {
            socket.soTimeout = timeout
            socket.connect(InetSocketAddress("127.0.0.1", port), timeout)
            val request = buildProbeHttpRequest(url, credentials, method)
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            val input = socket.getInputStream()
            val line = StringBuilder()
            while (line.length < 1024) {
                val next = input.read()
                if (next == -1) throw IOException("Missing status")
                if (next == 10) break
                if (next != 13) line.append(next.toChar())
            }
            val status =
                Regex("HTTP/1\\.[01] ([0-9]{3})(?: .*)?")
                    .matchEntire(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: throw IOException("Invalid status")
            continuation.resume(status to SystemClock.elapsedRealtime())
        } catch (exception: Exception) {
            if (continuation.isActive) continuation.resumeWithException(exception)
        }
    }
}

private suspend fun Call.probeResponse(): Pair<Int, Long> =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { continuation.resume(it.code to SystemClock.elapsedRealtime()) }
                }
            }
        )
    }

internal fun buildProbeConfig(
    config: String,
    port: Int,
    username: String,
    password: String,
): String {
    val original = JSONObject(config)
    val outbounds = original.getJSONArray("outbounds")
    val selected = outbounds.getJSONObject(probeOutboundIndex(config))
    val tag = selected.optString("tag").ifBlank { UUID.randomUUID().toString() }
    selected.put("tag", tag)
    val inboundTag = UUID.randomUUID().toString()
    val result = JSONObject()
    listOf("dns", "transport", "policy", "fakedns").forEach { key ->
        if (original.has(key)) result.put(key, original.get(key))
    }
    result.put("log", JSONObject().put("loglevel", "none"))
    result.put("outbounds", outbounds)
    result.put(
        "inbounds",
        JSONArray()
            .put(
                JSONObject()
                    .put("tag", inboundTag)
                    .put("listen", "127.0.0.1")
                    .put("port", port)
                    .put("protocol", "http")
                    .put(
                        "settings",
                        JSONObject()
                            .put(
                                "accounts",
                                JSONArray()
                                    .put(JSONObject().put("user", username).put("pass", password)),
                            ),
                    )
            ),
    )
    val routing = original.optJSONObject("routing") ?: JSONObject().put("domainStrategy", "AsIs")
    val rules =
        JSONArray()
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put(inboundTag))
                    .put("outboundTag", tag)
            )
    routing.optJSONArray("rules")?.let { old ->
        for (index in 0 until old.length()) rules.put(old.get(index))
    }
    result.put("routing", routing.put("rules", rules))
    return result.toString()
}
