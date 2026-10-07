package com.simplexray.an.feature.subscriptions

import android.app.Application
import com.simplexray.an.core.network.socks.LocalProxyEndpoint
import com.simplexray.an.feature.subscriptions.data.remote.SubscriptionDocumentClient
import com.simplexray.an.prefs.Preferences
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Authenticator
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SubscriptionActiveProxyTest {
    @Test
    fun requestUsesActiveAddressPortAndCredentialsAfterPendingEdits() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.2")).use { proxy ->
                proxy.soTimeout = 5000
                prefs.activeProxySettingsJson =
                    LocalProxyEndpoint("127.0.0.2", proxy.localPort, "active", "secret").encode()
                prefs.socksAddress = "127.0.0.1"
                prefs.socksPort = 1
                prefs.socksUsername = "draft"
                prefs.socksPassword = "draft-secret"
                val serving =
                    executor.submit {
                        proxy.accept().use { socket ->
                            socket.soTimeout = 5000
                            val input = DataInputStream(socket.getInputStream())
                            val output = DataOutputStream(socket.getOutputStream())
                            assertEquals(5, input.readUnsignedByte())
                            input.readFully(ByteArray(input.readUnsignedByte()))
                            output.write(byteArrayOf(5, 2))
                            assertEquals(1, input.readUnsignedByte())
                            fun text() =
                                String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                            assertEquals("active", text())
                            assertEquals("secret", text())
                            output.write(byteArrayOf(1, 0))
                            assertEquals(5, input.readUnsignedByte())
                            assertEquals(1, input.readUnsignedByte())
                            input.readUnsignedByte()
                            when (input.readUnsignedByte()) {
                                1 -> input.readFully(ByteArray(4))
                                3 -> assertEquals("source.invalid", text())
                                4 -> input.readFully(ByteArray(16))
                                else -> error("Unexpected address type")
                            }
                            assertEquals(80, input.readUnsignedShort())
                            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 2, 0, 80))
                            val reader = input.bufferedReader()
                            assertEquals("GET /feed HTTP/1.1", reader.readLine())
                            while (!reader.readLine().isNullOrEmpty()) {}
                            output.write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                                    .toByteArray()
                            )
                        }
                    }
                val response =
                    SubscriptionDocumentClient(prefs) { true }
                        .fetchDocument("http://source.invalid/feed", false, true)
                assertEquals("OK", response.body)
                serving.get(5, TimeUnit.SECONDS)
            }
        } finally {
            Authenticator.setDefault(null)
            executor.shutdownNow()
        }
    }
}
