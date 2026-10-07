package com.simplexray.an.core.runtime

import com.google.protobuf.CodedOutputStream
import com.simplexray.an.core.runtime.process.CoreRuntimeProcess
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class CoreGeodataCoreTest {
    private fun encoded(block: (CodedOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        CodedOutputStream.newInstance(buffer).also {
            block(it)
            it.flush()
        }
        return buffer.toByteArray()
    }

    private fun database(file: File, domain: String) {
        file.writeBytes(
            encoded { list ->
                list.writeByteArray(
                    1,
                    encoded { site ->
                        site.writeString(1, "TEST")
                        site.writeByteArray(
                            2,
                            encoded { entry ->
                                entry.writeEnum(1, 3)
                                entry.writeString(2, domain)
                            },
                        )
                    },
                )
            }
        )
    }

    private fun port() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    @Test
    fun geositeAndCustomRulesPreserveDirectAndBlockedTrafficAcrossDatabaseChanges() {
        val core = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)
        assumeTrue("Host Xray must be explicitly supplied", core?.canExecute() == true)
        val dir = Files.createTempDirectory("geodata-core").toFile()
        val assets = File(dir, "assets").apply { mkdir() }
        val db = File(assets, "geosite.dat")
        val executable = requireNotNull(core)
        File(assets, "matcher.cache").writeText("obsolete cache")
        val http = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val responder =
            thread(isDaemon = true) {
                while (!http.isClosed) runCatching {
                    http.accept().use { socket ->
                        socket.soTimeout = 2000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        socket
                            .getOutputStream()
                            .write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                                    .toByteArray()
                            )
                    }
                }
            }
        try {
            for (pattern in
                listOf(
                    "geosite:test",
                    "full:localhost",
                    "domain:localhost",
                    "regexp:^localhost$",
                )) {
                database(db, if (pattern == "geosite:test") "localhost" else "example.invalid")
                val listen = port()
                val config =
                    """{"inbounds":[{"tag":"client","listen":"127.0.0.1","port":$listen,"protocol":"http"}],"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],"routing":{"rules":[{"ruleTag":"geosite","domain":["geosite:test"],"inboundTag":["client"],"outboundTag":"block"},{"ruleTag":"custom","domain":["$pattern"],"inboundTag":["client"],"outboundTag":"block"}]}}"""
                repeat(2) {
                    verifyTraffic(
                        executable,
                        assets,
                        config,
                        listen,
                        http.localPort,
                        blocked = true,
                    )
                }
                if (pattern == "geosite:test") {
                    database(db, "example.invalid")
                    verifyTraffic(
                        executable,
                        assets,
                        config,
                        listen,
                        http.localPort,
                        blocked = false,
                    )
                    database(db, "localhost")
                    verifyTraffic(
                        executable,
                        assets,
                        config,
                        listen,
                        http.localPort,
                        blocked = true,
                    )
                }
            }
        } finally {
            http.close()
            responder.join(2000)
            dir.deleteRecursively()
        }
    }

    private fun verifyTraffic(
        core: File,
        assets: File,
        config: String,
        listen: Int,
        http: Int,
        blocked: Boolean,
    ) {
        val log = File(assets, "core.log")
        val child = CoreRuntimeProcess.builder(core, assets).redirectOutput(log).start()
        try {
            child.outputStream.use { it.write(config.toByteArray()) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var ready = false
            while (child.isAlive && !ready && System.nanoTime() < deadline) {
                ready =
                    runCatching {
                            Socket().use { it.connect(InetSocketAddress("127.0.0.1", listen), 100) }
                        }
                        .isSuccess
                if (!ready) Thread.sleep(20)
            }
            assertTrue("Core must open its listener: ${log.readText()}", ready)
            fun request(host: String): Boolean =
                runCatching {
                        Socket("127.0.0.1", listen).use { socket ->
                            socket.soTimeout = 2000
                            socket
                                .getOutputStream()
                                .write(
                                    "GET http://$host:$http/ HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                            socket
                                .getInputStream()
                                .bufferedReader()
                                .readLine()
                                .orEmpty()
                                .contains("200 OK")
                        }
                    }
                    .getOrDefault(false)
            assertTrue("Unmatched traffic must remain direct", request("127.0.0.1"))
            assertEquals(
                "Geodata and custom rules must retain domain selection",
                !blocked,
                request("localhost"),
            )
        } finally {
            child.destroyForcibly()
            child.waitFor(2, TimeUnit.SECONDS)
        }
    }
}
