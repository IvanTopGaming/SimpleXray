package com.simplexray.an.feature.servers.data

import com.simplexray.an.feature.servers.model.ServerCheckResult
import com.simplexray.an.feature.servers.model.ServerCheckState
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ServerChecks(
    private val scope: CoroutineScope,
    private val readConfig: suspend (File) -> String,
    private val probe: suspend (String) -> ServerCheckResult,
) {
    private val current = MutableStateFlow(ServerCheckState())
    val state = current.asStateFlow()
    private var batch: Job? = null
    private var generation = 0L
    private val fingerprints = mutableMapOf<String, String>()
    private val resultFingerprints = mutableMapOf<String, String>()

    @Synchronized
    fun start(files: List<File>, batchProbe: suspend (String) -> ServerCheckResult = probe) {
        if (current.value.running || files.isEmpty()) return
        val unique = files.distinctBy { it.absolutePath }
        val paths = unique.map { it.absolutePath }.toSet()
        current.value =
            ServerCheckState(
                running = true,
                total = unique.size,
                results = current.value.results.filterKeys { it !in paths },
            )
        paths.forEach(resultFingerprints::remove)
        val token = generation
        val job =
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val semaphore = Semaphore(2)
                coroutineScope {
                    unique.forEach { file ->
                        launch { semaphore.withPermit { check(file, token, batchProbe) } }
                    }
                }
            }
        batch = job
        job.invokeOnCompletion { cause ->
            synchronized(this) {
                if (batch === job)
                    current.update {
                        it.copy(
                            running = false,
                            checking = emptySet(),
                            cancelled = it.cancelled || cause is CancellationException,
                        )
                    }
            }
        }
        job.start()
    }

    @Synchronized
    fun cancel() {
        if (!current.value.running) return
        current.update { it.copy(cancelled = true) }
        batch?.cancel()
    }

    @Synchronized
    fun reset() {
        generation++
        batch?.cancel()
        batch = null
        resultFingerprints.clear()
        current.value = ServerCheckState()
    }

    @Synchronized
    fun invalidate(configs: Map<String, String>) {
        fingerprints.clear()
        fingerprints.putAll(configs.mapValues { fingerprint(it.value) })
        val valid =
            current.value.results.filterKeys {
                fingerprints[it] != null && fingerprints[it] == resultFingerprints[it]
            }
        resultFingerprints.keys.retainAll(valid.keys)
        current.update { it.copy(results = valid) }
    }

    private suspend fun check(
        file: File,
        token: Long,
        batchProbe: suspend (String) -> ServerCheckResult,
    ) {
        val path = file.absolutePath
        synchronized(this) {
            if (token != generation) return
            current.update { it.copy(checking = it.checking + path) }
        }
        var hash: String? = null
        var result: ServerCheckResult? = null
        var unchanged = false
        try {
            val config = readConfig(file)
            hash = fingerprint(config)
            synchronized(this) { fingerprints.putIfAbsent(path, hash) }
            result =
                try {
                    batchProbe(config)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    ServerCheckResult(error = "Проверка не удалась")
                }
            unchanged = fingerprint(readConfig(file)) == hash
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            unchanged = false
        }
        currentCoroutineContext().ensureActive()
        synchronized(this) {
            if (token != generation) return
            val accepted = unchanged && hash != null && fingerprints[path] == hash && result != null
            if (accepted) resultFingerprints[path] = hash!!
            current.update {
                it.copy(
                    completed = it.completed + 1,
                    available = it.available + if (accepted && result?.latencyMs != null) 1 else 0,
                    unavailable =
                        it.unavailable + if (accepted && result?.latencyMs == null) 1 else 0,
                    skipped = it.skipped + if (accepted) 0 else 1,
                    checking = it.checking - path,
                    results = if (accepted) it.results + (path to result!!) else it.results,
                )
            }
        }
    }

    private fun fingerprint(config: String): String =
        MessageDigest.getInstance("SHA-256").digest(config.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
