package com.simplexray.an.core.geodata

import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeodataSourceRefreshTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun forcedRefreshPublishesRequiredDatabaseBeforeSnapshotWithoutDownloadingOtherFiles() =
        runBlocking {
            val directory = temporary.newFolder()
            val old = geosite("old.test")
            val updated = geosite("new.test")
            File(directory, "geosite.dat").writeBytes(old)
            File(directory, "geoip.dat").writeBytes(byteArrayOf(1, 2, 3))
            val published = mutableListOf<String>()
            RefreshServer(updated).use { server ->
                val manager =
                    GeodataManager(
                        directory,
                        { name ->
                            assertEquals("geosite.dat", name)
                            server.url
                        },
                    )
                manager.withAvailable(
                    setOf("geoip.dat", "geosite.dat"),
                    refresh = setOf("geosite.dat"),
                    onPublished = { name ->
                        assertArrayEquals(updated, File(directory, name).readBytes())
                        published += name
                    },
                ) {
                    assertEquals(listOf("geosite.dat"), published)
                    assertArrayEquals(updated, File(directory, "geosite.dat").readBytes())
                }
                assertEquals(1, server.requests.get())
                assertArrayEquals(byteArrayOf(1, 2, 3), File(directory, "geoip.dat").readBytes())
            }
        }

    @Test
    fun failedRefreshPreservesOldFileAndNeverAcknowledgesOrCreatesSnapshot() = runBlocking {
        val directory = temporary.newFolder()
        val old = geosite("old.test")
        File(directory, "geosite.dat").writeBytes(old)
        val pending = mutableSetOf("geosite.dat")
        RefreshServer("unavailable".toByteArray(), 503).use { server ->
            try {
                GeodataManager(directory, { server.url }).withAvailable(
                    setOf("geosite.dat"),
                    refresh = pending.toSet(),
                    onPublished = { pending.remove(it) },
                ) {
                    fail("Failed refresh must not produce a snapshot")
                }
                fail("Refresh should fail")
            } catch (_: IOException) {}
            assertEquals(setOf("geosite.dat"), pending)
            assertArrayEquals(old, File(directory, "geosite.dat").readBytes())
        }
    }

    @Test
    fun pendingButUnusedDatabaseDoesNotDownloadOrAcknowledge() = runBlocking {
        val directory = temporary.newFolder()
        File(directory, "geoip.dat").writeBytes(byteArrayOf(1))
        GeodataManager(directory, { error("Unused database must not download") }).withAvailable(
            setOf("geoip.dat"),
            refresh = setOf("geosite.dat"),
            onPublished = { error("Unused database must remain pending") },
        ) {
            assertFalse(File(directory, "geosite.dat").exists())
        }
    }

    private fun geosite(domain: String): ByteArray = message {
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

private class RefreshServer(private val bytes: ByteArray, private val status: Int = 200) :
    Closeable {
    private val server = ServerSocket(0)
    private val executor = Executors.newSingleThreadExecutor()
    val requests = AtomicInteger()
    val url = "http://127.0.0.1:${server.localPort}/geosite.dat"

    init {
        executor.submit {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        requests.incrementAndGet()
                        socket.getOutputStream().apply {
                            write(
                                "HTTP/1.1 $status Result\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                    .toByteArray()
                            )
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (_: IOException) {}
        }
    }

    override fun close() {
        server.close()
        executor.shutdownNow()
    }
}
