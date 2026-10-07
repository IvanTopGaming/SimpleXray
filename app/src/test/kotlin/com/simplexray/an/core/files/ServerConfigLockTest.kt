package com.simplexray.an.core.files

import com.simplexray.an.core.files.config.ServerConfigLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class ServerConfigLockTest {
    @Test
    fun readerSeesWholePublishedGenerationAndWriterHoldsAnOsLock() {
        val directory = Files.createTempDirectory("server-config-lock").toFile()
        val config = File(directory, "server.json")
        val metadata = File(directory, "subscriptions.json")
        val writing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val snapshot = AtomicReference<Pair<String, String>>()
        val writer = thread {
            ServerConfigLock.withSnapshot(directory) {
                config.writeText("new-config")
                writing.countDown()
                finish.await(5, TimeUnit.SECONDS)
                metadata.writeText("new-metadata")
            }
        }
        var reader: Thread? = null
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            RandomAccessFile(File(directory, ".server-configs.lock"), "rw").use {
                assertThrows(OverlappingFileLockException::class.java) {
                    it.channel.tryLock()?.release()
                }
            }
            reader = thread {
                reading.countDown()
                snapshot.set(
                    ServerConfigLock.withSnapshot(directory) {
                        config.readText() to metadata.readText()
                    }
                )
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertNull(snapshot.get())
            finish.countDown()
            writer.join(5000)
            reader.join(5000)
            assertEquals("new-config" to "new-metadata", snapshot.get())
        } finally {
            finish.countDown()
            writer.join(5000)
            reader?.join(5000)
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedOperationReleasesBothLocks() {
        val directory = Files.createTempDirectory("server-config-lock-error").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ServerConfigLock.withSnapshot(directory) {
                    throw IllegalArgumentException("fixture")
                }
            }
            assertEquals("ready", ServerConfigLock.withSnapshot(directory) { "ready" })
        } finally {
            directory.deleteRecursively()
        }
    }
}
