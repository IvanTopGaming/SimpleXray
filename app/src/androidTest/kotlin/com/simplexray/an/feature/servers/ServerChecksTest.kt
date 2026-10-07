package com.simplexray.an.feature.servers

import com.simplexray.an.feature.servers.data.ServerChecks
import com.simplexray.an.feature.servers.model.ServerCheckResult
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ServerChecksTest {
    @Test
    fun cancelledOwnerCannotLeaveBatchStuckBeforeBodyEntry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.cancel()
        val checks = ServerChecks(scope, { "config" }) { error("Cancelled owner must not probe") }
        checks.start(listOf(File("/fixture/a")))
        withTimeout(3000) { while (checks.state.value.running) delay(10) }
        assertFalse(
            "Completion must release running state even when body never executes",
            checks.state.value.running,
        )
        assertTrue(checks.state.value.cancelled)
        assertTrue(checks.state.value.checking.isEmpty())
    }

    @Test
    fun boundedBatchReportsProgressAndIgnoresDuplicateStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val files = (1..3).map { File("/fixture/$it.json") }
        val checks =
            ServerChecks(scope, { it.name }) {
                entered.incrementAndGet()
                val count = active.incrementAndGet()
                maximum.updateAndGet { previous -> maxOf(previous, count) }
                try {
                    release.await()
                    ServerCheckResult(latencyMs = 45)
                } finally {
                    active.decrementAndGet()
                }
            }
        try {
            checks.start(files)
            withTimeout(3000) { while (entered.get() < 2) delay(10) }
            checks.start(files)
            delay(50)
            assertEquals(2, entered.get())
            assertTrue(checks.state.value.running)
            assertEquals(3, checks.state.value.total)
            assertEquals(2, checks.state.value.checking.size)
            release.complete(Unit)
            withTimeout(3000) { while (checks.state.value.running) delay(10) }
            assertEquals(3, checks.state.value.completed)
            assertEquals(3, checks.state.value.available)
            assertEquals(3, checks.state.value.results.size)
            assertEquals(2, maximum.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationStopsWorkAndAllowsAnotherBatch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val first = AtomicBoolean(true)
        val checks =
            ServerChecks(scope, { "config" }) {
                if (first.getAndSet(false)) {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        exited.complete(Unit)
                    }
                } else ServerCheckResult(latencyMs = 30)
            }
        try {
            checks.start(listOf(File("/fixture/server")))
            withTimeout(3000) { entered.await() }
            checks.cancel()
            withTimeout(3000) {
                exited.await()
                while (checks.state.value.running) delay(10)
            }
            assertTrue(checks.state.value.cancelled)
            assertTrue(checks.state.value.checking.isEmpty())
            assertTrue(checks.state.value.results.isEmpty())
            checks.start(listOf(File("/fixture/server")))
            withTimeout(3000) { while (checks.state.value.running) delay(10) }
            assertFalse(checks.state.value.cancelled)
            assertEquals(1, checks.state.value.available)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun changedOrDeletedConfigsCannotReceiveOldResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val configs = ConcurrentHashMap(mapOf("/fixture/a" to "old", "/fixture/b" to "old"))
        val entered = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val checks =
            ServerChecks(scope, { configs[it.path] ?: error("deleted") }) {
                entered.incrementAndGet()
                release.await()
                ServerCheckResult(latencyMs = 30)
            }
        try {
            checks.invalidate(configs)
            checks.start(configs.keys.map(::File))
            withTimeout(3000) { while (entered.get() < 2) delay(10) }
            configs["/fixture/a"] = "new"
            configs.remove("/fixture/b")
            checks.invalidate(configs)
            release.complete(Unit)
            withTimeout(3000) { while (checks.state.value.running) delay(10) }
            assertTrue(checks.state.value.results.isEmpty())
            assertEquals(2, checks.state.value.skipped)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun completedResultsInvalidateOnlyWhenContentChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val checks = ServerChecks(scope, { "old" }) { ServerCheckResult(error = "Не отвечает") }
        try {
            checks.start(listOf(File("/fixture/a")))
            withTimeout(3000) { while (checks.state.value.running) delay(10) }
            assertEquals(1, checks.state.value.unavailable)
            checks.invalidate(mapOf("/fixture/a" to "old"))
            assertEquals(1, checks.state.value.results.size)
            checks.invalidate(mapOf("/fixture/a" to "new"))
            assertTrue(checks.state.value.results.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
