package com.simplexray.an.common.configFormat

import android.content.Context
import androidx.core.net.toUri
import com.simplexray.an.prefs.Preferences
import org.json.JSONObject

class TrojanLinkConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.startsWith("trojan://")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> {
        return try {
            val url = content.toUri()
            val name = url.fragment ?: ("imported_trojan_" + System.currentTimeMillis())
            val address = url.host ?: return Result.failure(RuntimeException("Missing host"))
            val port = url.port.takeIf { it != -1 } ?: 443
            val password = url.userInfo ?: return Result.failure(RuntimeException("Missing password"))

            val network = url.getQueryParameter("type") ?: "tcp"
            val sni = url.getQueryParameter("sni") ?: url.getQueryParameter("peer") ?: address
            val host = url.getQueryParameter("host") ?: ""
            val path = url.getQueryParameter("path") ?: "/"

            val socksPort = Preferences(context).socksPort

            val streamSettings = JSONObject()
                .put("network", network)
                .put("security", "tls")
                .put("tlsSettings", JSONObject().put("serverName", sni))
            when (network) {
                "ws" -> streamSettings.put(
                    "wsSettings",
                    JSONObject().put("path", path).put(
                        "headers",
                        JSONObject().apply { if (host.isNotEmpty()) put("Host", host) }
                    )
                )
                "grpc" -> streamSettings.put(
                    "grpcSettings",
                    JSONObject().put("serviceName", path.trimStart('/'))
                )
            }

            val config = JSONObject(
                mapOf(
                    "log" to mapOf("loglevel" to "warning"),
                    "inbounds" to listOf(
                        mapOf(
                            "port" to socksPort,
                            "listen" to "127.0.0.1",
                            "protocol" to "socks",
                            "settings" to mapOf("udp" to true)
                        )
                    ),
                    "outbounds" to listOf(
                        mapOf(
                            "protocol" to "trojan",
                            "settings" to mapOf(
                                "servers" to listOf(
                                    mapOf(
                                        "address" to address,
                                        "port" to port,
                                        "password" to password
                                    )
                                )
                            )
                        )
                    )
                )
            )
            config.getJSONArray("outbounds").getJSONObject(0).put("streamSettings", streamSettings)

            Result.success(DetectedConfig(name, config.toString(2)))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
