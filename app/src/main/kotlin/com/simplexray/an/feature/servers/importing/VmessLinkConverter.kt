package com.simplexray.an.feature.servers.importing

import android.content.Context
import com.simplexray.an.prefs.Preferences
import java.util.Base64
import org.json.JSONObject

class VmessLinkConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.startsWith("vmess://")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> {
        return try {
            val payload = content.substring("vmess://".length).trim()
            val decoded = String(Base64.getDecoder().decode(padBase64(payload)))
            val v = JSONObject(decoded)

            val name = v.optString("ps").ifEmpty { "imported_vmess_" + System.currentTimeMillis() }
            val address =
                v.optString("add").ifEmpty {
                    return Result.failure(RuntimeException("Missing add"))
                }
            val port = v.optString("port").toIntOrNull() ?: 443
            val id =
                v.optString("id").ifEmpty {
                    return Result.failure(RuntimeException("Missing id"))
                }
            val alterId = v.optString("aid").toIntOrNull() ?: 0
            val security = v.optString("scy").ifEmpty { "auto" }
            val network =
                v.optString("net").ifEmpty { "tcp" }.let { if (it == "h2") "http" else it }
            val host = v.optString("host")
            val path = v.optString("path").ifEmpty { "/" }
            val tls = v.optString("tls")
            val sni = v.optString("sni").ifEmpty { host.ifEmpty { address } }

            val socksPort = Preferences(context).socksPort

            val streamSettings = JSONObject().put("network", network)
            if (tls == "tls") {
                streamSettings.put("security", "tls")
                streamSettings.put("tlsSettings", JSONObject().put("serverName", sni))
            }
            when (network) {
                "ws" ->
                    streamSettings.put(
                        "wsSettings",
                        JSONObject()
                            .put("path", path)
                            .put(
                                "headers",
                                JSONObject().apply { if (host.isNotEmpty()) put("Host", host) },
                            ),
                    )
                "grpc" ->
                    streamSettings.put(
                        "grpcSettings",
                        JSONObject().put("serviceName", path.trimStart('/')),
                    )
                "http" ->
                    streamSettings.put(
                        "httpSettings",
                        JSONObject().put("path", path).apply {
                            if (host.isNotEmpty()) put("host", listOf(host))
                        },
                    )
            }

            val config =
                JSONObject(
                    mapOf(
                        "log" to mapOf("loglevel" to "warning"),
                        "inbounds" to
                            listOf(
                                mapOf(
                                    "port" to socksPort,
                                    "listen" to "127.0.0.1",
                                    "protocol" to "socks",
                                    "settings" to mapOf("udp" to true),
                                )
                            ),
                        "outbounds" to
                            listOf(
                                mapOf(
                                    "protocol" to "vmess",
                                    "settings" to
                                        mapOf(
                                            "vnext" to
                                                listOf(
                                                    mapOf(
                                                        "address" to address,
                                                        "port" to port,
                                                        "users" to
                                                            listOf(
                                                                mapOf(
                                                                    "id" to id,
                                                                    "alterId" to alterId,
                                                                    "security" to security,
                                                                )
                                                            ),
                                                    )
                                                )
                                        ),
                                )
                            ),
                    )
                )
            config.getJSONArray("outbounds").getJSONObject(0).put("streamSettings", streamSettings)

            Result.success(DetectedConfig(name, config.toString(2)))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun padBase64(s: String): String {
        val clean = s.replace("-", "+").replace("_", "/")
        val pad = (4 - clean.length % 4) % 4
        return clean + "=".repeat(pad)
    }
}
