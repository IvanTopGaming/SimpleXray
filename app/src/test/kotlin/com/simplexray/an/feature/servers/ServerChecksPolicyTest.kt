package com.simplexray.an.feature.servers

import com.simplexray.an.feature.servers.data.ServerChecks
import com.simplexray.an.feature.servers.model.ServerCheckResult
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ServerChecksPolicyTest {
    @Test
    fun resetDiscardsResultsFromCancelledPolicy() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val checker =
            ServerChecks(
                this,
                { "config" },
                {
                    started.complete(Unit)
                    withContext(NonCancellable) { finish.await() }
                    ServerCheckResult(latencyMs = 1)
                },
            )
        checker.start(listOf(File("server.json")))
        withTimeout(3000) { started.await() }
        checker.reset()
        finish.complete(Unit)
        coroutineContext[Job]!!.children.toList().joinAll()
        assertFalse(checker.state.value.running)
        assertEquals(0, checker.state.value.completed)
        assertTrue(checker.state.value.results.isEmpty())
    }

    @Test
    fun eachBatchUsesSuppliedProbeSnapshot() = runBlocking {
        val checker = ServerChecks(this, { "config" }, { error("default must not run") })
        checker.start(listOf(File("server.json"))) { ServerCheckResult(latencyMs = 42) }
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(42L, checker.state.value.results.values.single().latencyMs)
        checker.reset()
        assertTrue(checker.state.value.results.isEmpty())
    }
}
