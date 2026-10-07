package com.simplexray.an.core.geodata

import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeodataManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun onlyRequiredMissingFilesAreDownloadedAndEmptyFilesAreReplaced() = runBlocking {
        val directory = temporary.newFolder()
        File(directory, "geoip.dat").writeBytes(byteArrayOf())
        val progress = mutableListOf<String>()
        GeodataServer(geoip()).use { server ->
            val manager = GeodataManager(directory, { server.url })
            manager.ensureAvailable(emptySet())
            assertEquals(0, server.requests.get())
            manager.ensureAvailable(setOf("geoip.dat"), progress::add)
            manager.ensureAvailable(setOf("geoip.dat"))
            assertEquals(1, server.requests.get())
            assertArrayEquals(geoip(), File(directory, "geoip.dat").readBytes())
            assertFalse(File(directory, "geosite.dat").exists())
            assertTrue(
                progress.any { it.contains("geoip.dat") && it.contains(geoip().size.toString()) }
            )
            assertEquals(listOf("geoip.dat"), dataFiles(directory))
        }
    }

    @Test
    fun existingNonemptyFilesArePreservedWithoutNetworkAccess() = runBlocking {
        val directory = temporary.newFolder()
        val existing = byteArrayOf(1, 2, 3)
        File(directory, "geoip.dat").writeBytes(existing)
        GeodataManager(directory, { error("Should not resolve URL") })
            .ensureAvailable(setOf("geoip.dat"))
        assertArrayEquals(existing, File(directory, "geoip.dat").readBytes())
    }

    @Test
    fun importsValidateBothFormatsAndRejectUnsafeFilenames() = runBlocking {
        val directory = temporary.newFolder()
        val manager = GeodataManager(directory, { error("No download") })
        manager.replace("geoip.dat", ByteArrayInputStream(geoip()))
        manager.replace("geosite.dat", ByteArrayInputStream(geosite()))
        assertArrayEquals(geosite(), File(directory, "geosite.dat").readBytes())
        expectFailure { manager.ensureAvailable(setOf("../geoip.dat")) }
        expectFailure { manager.replace("config.json", ByteArrayInputStream(geoip())) }
    }

    @Test
    fun malformedAndTruncatedDownloadsNeverPublish() = runBlocking {
        val malformed =
            listOf(
                byteArrayOf(),
                "<html>server error</html>".toByteArray(),
                geoip().dropLast(1).toByteArray(),
                message { writeByteArray(1, message { writeString(1, "TEST") }) },
                message {
                    writeByteArray(
                        1,
                        message {
                            writeString(1, "TEST")
                            writeByteArray(
                                2,
                                message {
                                    writeByteArray(1, byteArrayOf(1))
                                    writeUInt32(2, 24)
                                },
                            )
                        },
                    )
                },
            )
        for (bytes in malformed) {
            val directory = temporary.newFolder()
            GeodataServer(bytes).use { server ->
                expectFailure {
                    GeodataManager(directory, { server.url }).ensureAvailable(setOf("geoip.dat"))
                }
                assertTrue(dataFiles(directory).isEmpty())
            }
        }
    }

    @Test
    fun failedUpdatesAndImportsPreserveInstalledFile() = runBlocking {
        val directory = temporary.newFolder()
        val target = File(directory, "geosite.dat")
        target.writeBytes(geosite())
        GeodataServer("unavailable".toByteArray(), status = 503).use { server ->
            val manager = GeodataManager(directory, { server.url })
            expectFailure { manager.update("geosite.dat") }
            expectFailure { manager.replace("geosite.dat", ByteArrayInputStream(geoip())) }
            assertArrayEquals(geosite(), target.readBytes())
            assertEquals(listOf("geosite.dat"), dataFiles(directory))
        }
    }

    @Test
    fun oversizedResponseIsRejectedBeforeReadingBody() = runBlocking {
        val directory = temporary.newFolder()
        GeodataServer(geoip(), declaredLength = 1024L * 1024 * 1024).use { server ->
            expectFailure {
                GeodataManager(directory, { server.url }).ensureAvailable(setOf("geoip.dat"))
            }
            assertTrue(dataFiles(directory).isEmpty())
        }
    }

    @Test
    fun cancellationInterruptsStalledBodyAndCleansTemporaryFile() = runBlocking {
        val directory = temporary.newFolder()
        val target = File(directory, "geoip.dat")
        target.writeBytes(geoip())
        GeodataServer(geoip(), stall = true).use { server ->
            val job =
                launch(Dispatchers.IO) {
                    GeodataManager(directory, { server.url }).update("geoip.dat")
                }
            assertTrue(server.started.await(5, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertArrayEquals(geoip(), target.readBytes())
            assertEquals(listOf("geoip.dat"), dataFiles(directory))
        }
    }

    @Test
    fun concurrentManagersDownloadMissingFileOnlyOnce() = runBlocking {
        val directory = temporary.newFolder()
        GeodataServer(geoip()).use { server ->
            coroutineScope {
                List(6) {
                        async(Dispatchers.IO) {
                            GeodataManager(directory, { server.url })
                                .ensureAvailable(setOf("geoip.dat"))
                        }
                    }
                    .forEach { it.await() }
            }
            assertEquals(1, server.requests.get())
        }
    }

    @Test
    fun validationBlockPreventsReplacementUntilReaderFinishes() = runBlocking {
        val directory = temporary.newFolder()
        val target = File(directory, "geosite.dat")
        val initial = geosite("initial.example")
        val replacement = geosite("replacement.example")
        target.writeBytes(initial)
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempting = CompletableDeferred<Unit>()
        val first = GeodataManager(directory, { error("No download") })
        val reader =
            launch(Dispatchers.IO) {
                first.withAvailable(setOf("geosite.dat")) {
                    reading.complete(Unit)
                    release.await()
                    assertArrayEquals(initial, target.readBytes())
                }
            }
        reading.await()
        val writer =
            async(Dispatchers.IO) {
                attempting.complete(Unit)
                GeodataManager(directory, { error("No download") })
                    .replace("geosite.dat", ByteArrayInputStream(replacement))
            }
        attempting.await()
        assertNull(withTimeoutOrNull(200) { writer.await() })
        release.complete(Unit)
        reader.join()
        writer.await()
        assertArrayEquals(replacement, target.readBytes())
        assertEquals(listOf("geosite.dat"), dataFiles(directory))
    }

    @Test
    fun finalFileStaysCompleteWhileReplacementIsStreaming() = runBlocking {
        val directory = temporary.newFolder()
        val target = File(directory, "geosite.dat")
        val initial = geosite("initial.example")
        val replacement = geosite("replacement.example")
        target.writeBytes(initial)
        var progressBytes = 0
        val stream =
            object : ByteArrayInputStream(replacement) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    super.read(bytes, offset, minOf(length, 3))
            }
        GeodataManager(directory, { error("No download") }).replace("geosite.dat", stream) { count
            ->
            progressBytes += count
            assertArrayEquals(initial, target.readBytes())
        }
        assertEquals(replacement.size, progressBytes)
        assertArrayEquals(replacement, target.readBytes())
    }

    @Test
    fun unknownLengthInputCannotExceedLimitOrReplaceInstalledFile() = runBlocking {
        val directory = temporary.newFolder()
        val target = File(directory, "geoip.dat")
        target.writeBytes(geoip())
        var consumed = 0L
        val input =
            object : InputStream() {
                override fun read(): Int = 0

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    consumed += length
                    return length
                }
            }
        expectFailure {
            GeodataManager(directory, { error("No download") }).replace("geoip.dat", input)
        }
        assertTrue(consumed > 128L * 1024 * 1024)
        assertTrue(consumed <= 128L * 1024 * 1024 + 32 * 1024)
        assertArrayEquals(geoip(), target.readBytes())
        assertEquals(listOf("geoip.dat"), dataFiles(directory))
    }

    @Test
    fun importCloseFailurePreservesInstalledFile() = runBlocking {
        val directory = temporary.newFolder()
        val target = File(directory, "geosite.dat")
        val initial = geosite("initial.example")
        target.writeBytes(initial)
        val input =
            object : ByteArrayInputStream(geosite("replacement.example")) {
                override fun close() {
                    throw IOException("Import source failed")
                }
            }
        expectFailure {
            GeodataManager(directory, { error("No download") }).replace("geosite.dat", input)
        }
        assertArrayEquals(initial, target.readBytes())
        assertEquals(listOf("geosite.dat"), dataFiles(directory))
    }

    @Test
    fun cancellationClosesStalledImportAndPreservesInstalledFile() = runBlocking {
        val directory = temporary.newFolder()
        val target = File(directory, "geoip.dat")
        target.writeBytes(geoip())
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val input =
            object : InputStream() {
                override fun read(): Int {
                    reading.countDown()
                    if (!closed.await(5, TimeUnit.SECONDS))
                        throw IOException("Read was not cancelled")
                    throw IOException("Closed")
                }

                override fun close() {
                    closed.countDown()
                }
            }
        val job =
            launch(Dispatchers.IO) {
                GeodataManager(directory, { error("No download") }).replace("geoip.dat", input)
            }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        withTimeout(2000) { job.cancelAndJoin() }
        assertEquals(0, closed.count.toInt())
        assertArrayEquals(geoip(), target.readBytes())
        assertEquals(listOf("geoip.dat"), dataFiles(directory))
    }

    @Test
    fun externallyLockedEnsureRechecksInstalledFileAfterLockRelease() = runBlocking {
        val directory = temporary.newFolder()
        GeodataServer(geoip()).use { server ->
            RandomAccessFile(File(directory, ".geodata.lock"), "rw").use { external ->
                val lock = external.channel.lock()
                val attempting = CompletableDeferred<Unit>()
                val pending =
                    async(Dispatchers.IO) {
                        attempting.complete(Unit)
                        GeodataManager(directory, { server.url })
                            .ensureAvailable(setOf("geoip.dat"))
                    }
                attempting.await()
                assertNull(withTimeoutOrNull(200) { pending.await() })
                assertEquals(0, server.requests.get())
                File(directory, "geoip.dat").writeBytes(geoip())
                lock.release()
                withTimeout(2000) { pending.await() }
                assertEquals(0, server.requests.get())
                assertEquals(listOf("geoip.dat"), dataFiles(directory))
            }
        }
    }

    @Test
    fun externallyLockedReplacementWaitsBeforeReadingOrPublishing() = runBlocking {
        val directory = temporary.newFolder()
        val target = File(directory, "geosite.dat")
        val initial = geosite("initial.example")
        val replacement = geosite("replacement.example")
        target.writeBytes(initial)
        val read = AtomicBoolean()
        RandomAccessFile(File(directory, ".geodata.lock"), "rw").use { external ->
            val lock = external.channel.lock()
            val attempting = CompletableDeferred<Unit>()
            val pending =
                async(Dispatchers.IO) {
                    attempting.complete(Unit)
                    GeodataManager(directory, { error("No download") }).replace(
                        "geosite.dat",
                        ByteArrayInputStream(replacement),
                    ) {
                        read.set(true)
                    }
                }
            attempting.await()
            assertNull(withTimeoutOrNull(200) { pending.await() })
            assertFalse(read.get())
            assertArrayEquals(initial, target.readBytes())
            lock.release()
            withTimeout(2000) { pending.await() }
            assertArrayEquals(replacement, target.readBytes())
        }
    }

    @Test
    fun cancellingFileLockWaitClosesImportAndLeavesNoTemporaryFiles() = runBlocking {
        val directory = temporary.newFolder()
        val target = File(directory, "geoip.dat")
        target.writeBytes(geoip())
        val closed = AtomicBoolean()
        val input =
            object : ByteArrayInputStream(geoip()) {
                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }
        RandomAccessFile(File(directory, ".geodata.lock"), "rw").use { external ->
            external.channel.lock().use {
                val attempting = CompletableDeferred<Unit>()
                val pending =
                    async(Dispatchers.IO) {
                        attempting.complete(Unit)
                        GeodataManager(directory, { error("No download") })
                            .replace("geoip.dat", input)
                    }
                attempting.await()
                assertNull(withTimeoutOrNull(200) { pending.await() })
                withTimeout(2000) { pending.cancelAndJoin() }
                assertTrue(closed.get())
                assertArrayEquals(geoip(), target.readBytes())
                assertEquals(listOf("geoip.dat"), dataFiles(directory))
                assertTrue(File(directory, ".geodata.lock").isFile)
            }
        }
        withTimeout(2000) {
            GeodataManager(directory, { error("No download") }).ensureAvailable(setOf("geoip.dat"))
        }
    }

    @Test
    fun nextOperationRemovesOnlyOrphanStagingFilesAndPreservesInstalledData() = runBlocking {
        val directory = temporary.newFolder()
        val target = File(directory, "geoip.dat")
        target.writeBytes(geoip())
        val orphanIp = File.createTempFile(".geoip.dat-", ".tmp", directory)
        val orphanSite = File.createTempFile(".geosite.dat-", ".tmp", directory)
        orphanIp.writeText("interrupted download")
        orphanSite.writeText("interrupted import")
        val unrelated =
            listOf("other.tmp", ".geoip.dat-custom.tmp", "geoip.dat-123.tmp").map { name ->
                File(directory, name).apply { writeText("keep") }
            }
        val unrelatedDirectory = File(directory, ".geoip.dat-456.tmp").apply { mkdir() }
        GeodataManager(directory, { error("Existing file needs no network") })
            .ensureAvailable(setOf("geoip.dat"))
        assertFalse(orphanIp.exists())
        assertFalse(orphanSite.exists())
        assertArrayEquals(geoip(), target.readBytes())
        unrelated.forEach { assertEquals("keep", it.readText()) }
        assertTrue(unrelatedDirectory.isDirectory)
        assertTrue(File(directory, ".geodata.lock").isFile)
    }

    private fun dataFiles(directory: File): List<String> =
        directory.list()!!.filter { it != ".geodata.lock" }.sorted()

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Operation should fail")
        } catch (expected: IOException) {
            assertFalse(expected.message.isNullOrBlank())
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.isNullOrBlank())
        }
    }

    private fun geoip(): ByteArray = message {
        writeByteArray(
            1,
            message {
                writeString(1, "TEST")
                writeByteArray(
                    2,
                    message {
                        writeByteArray(1, byteArrayOf(192.toByte(), 0, 2, 0))
                        writeUInt32(2, 24)
                    },
                )
            },
        )
    }

    private fun geosite(domain: String = "example.test"): ByteArray = message {
        writeByteArray(
            1,
            message {
                writeString(1, "TEST")
                writeByteArray(
                    2,
                    message {
                        writeEnum(1, 2)
                        writeString(2, domain)
                    },
                )
            },
        )
    }

    private fun message(block: CodedOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        CodedOutputStream.newInstance(bytes).apply {
            block()
            flush()
        }
        return bytes.toByteArray()
    }
}

private class GeodataServer(
    private val bytes: ByteArray,
    private val status: Int = 200,
    private val declaredLength: Long = bytes.size.toLong(),
    private val stall: Boolean = false,
) : Closeable {
    private val server = ServerSocket(0)
    private val executor = Executors.newCachedThreadPool()
    private val sockets = CopyOnWriteArrayList<Socket>()
    val requests = AtomicInteger()
    val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    val url = "http://127.0.0.1:${server.localPort}/data"

    init {
        executor.submit {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    sockets.add(socket)
                    executor.submit {
                        socket.use {
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) {}
                            requests.incrementAndGet()
                            val output = socket.getOutputStream()
                            output.write(
                                "HTTP/1.1 $status Result\r\nContent-Length: $declaredLength\r\nConnection: close\r\n\r\n"
                                    .toByteArray()
                            )
                            if (stall) {
                                output.write(bytes.take(1).toByteArray())
                                output.flush()
                                started.countDown()
                                release.await(10, TimeUnit.SECONDS)
                            } else {
                                output.write(bytes)
                                output.flush()
                                started.countDown()
                            }
                        }
                    }
                }
            } catch (_: IOException) {}
        }
    }

    override fun close() {
        release.countDown()
        sockets.forEach { it.close() }
        server.close()
        executor.shutdownNow()
    }
}
