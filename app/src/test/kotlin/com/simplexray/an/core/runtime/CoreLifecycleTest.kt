package com.simplexray.an.core.runtime

import com.simplexray.an.core.runtime.lifecycle.CoreLifecycleGate
import com.simplexray.an.core.runtime.process.CoreRuntimeProcess
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class CoreLifecycleTest {
    @Test
    fun stopCannotMissCreationAndPublicationInFlight() {
        val gate = CoreLifecycleGate()
        val token = gate.next()!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopping = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val child = AtomicReference<Process?>()
        val launcher = thread {
            gate.runIfCurrent(token) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                child.set(ProcessBuilder("sleep", "60").start())
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val stopper = thread {
            stopping.countDown()
            gate.stop { child.get()?.destroy() }
            stopped.countDown()
        }
        try {
            assertTrue(stopping.await(2, TimeUnit.SECONDS))
            assertFalse(
                "Stop must serialize with publishing the child",
                stopped.await(100, TimeUnit.MILLISECONDS),
            )
            release.countDown()
            launcher.join(2000)
            stopper.join(2000)
            assertEquals(0, stopped.count.toInt())
            assertTrue(
                "Stop must terminate the published process",
                child.get()!!.waitFor(1, TimeUnit.SECONDS),
            )
            assertFalse(child.get()!!.isAlive)
        } finally {
            release.countDown()
            launcher.join(2000)
            stopper.join(2000)
            child.get()?.let {
                it.destroyForcibly()
                it.waitFor(1, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun stoppedOrSupersededLaunchCannotCreateTunnelOrProcess() {
        val gate = CoreLifecycleGate()
        val old = gate.next()!!
        val current = gate.next()!!
        val created = AtomicBoolean(false)
        assertFalse(gate.runIfCurrent(old) { created.set(true) })
        gate.stop {}
        assertFalse(gate.runIfCurrent(current) { created.set(true) })
        assertNull(gate.next())
        assertFalse(created.get())
    }

    @Test
    fun runtimeReadsPreparedStdinEvenWhenDefaultConfigFileExists() {
        val executable = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)
        assumeTrue("Host Xray must be explicitly supplied", executable?.canExecute() == true)
        val dir = Files.createTempDirectory("routing-stdin").toFile()
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        File(dir, "config.json").writeText("""{"outbounds":[{"protocol":"not-a-protocol"}]}""")
        val child =
            CoreRuntimeProcess.builder(executable!!, dir).redirectOutput(File("/dev/null")).start()
        try {
            child.outputStream.use {
                it.write(
                    """{"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"http"}],"outbounds":[{"protocol":"freedom"}]}"""
                        .toByteArray()
                )
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var listening = false
            while (child.isAlive && System.nanoTime() < deadline && !listening) {
                listening =
                    runCatching {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 50) }
                    }
                        .isSuccess
                if (!listening) Thread.sleep(20)
            }
            assertTrue(
                "Prepared stdin config must open its listener despite config.json",
                listening,
            )
        } finally {
            child.destroyForcibly()
            child.waitFor(1, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }
}
