package com.simplexray.an.common.configFormat

import android.content.Context
import com.simplexray.an.prefs.Preferences
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

class ShadowsocksLinkConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.startsWith("ss://")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> {
        return try {
            val hashIndex = content.indexOf('#')
            val name = if (hashIndex != -1) {
                URLDecoder.decode(content.substring(hashIndex + 1), "UTF-8")
            } else {
                "imported_ss_" + System.currentTimeMillis()
            }
            val body = (if (hashIndex != -1) content.substring(0, hashIndex) else content)
                .substring("ss://".length)
                .substringBefore("?")

            val method: String
            val password: String
            val host: String
            val port: Int

            val atIndex = body.lastIndexOf('@')
            if (atIndex != -1) {
                val userInfo = String(Base64.getDecoder().decode(padBase64(body.substring(0, atIndex))))
                method = userInfo.substringBefore(":")
                password = userInfo.substringAfter(":")
                val hostPort = body.substring(atIndex + 1).substringBefore("/")
                host = hostPort.substringBeforeLast(":").removeSurrounding("[", "]")
                port = hostPort.substringAfterLast(":").toIntOrNull()
                    ?: return Result.failure(RuntimeException("Missing port"))
            } else {
                val decoded = String(Base64.getDecoder().decode(padBase64(body)))
                val methodPass = decoded.substringBefore("@")
                val hostPort = decoded.substringAfter("@")
                method = methodPass.substringBefore(":")
                password = methodPass.substringAfter(":")
                host = hostPort.substringBeforeLast(":").removeSurrounding("[", "]")
                port = hostPort.substringAfterLast(":").toIntOrNull()
                    ?: return Result.failure(RuntimeException("Missing port"))
            }

            val socksPort = Preferences(context).socksPort

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
                            "protocol" to "shadowsocks",
                            "settings" to mapOf(
                                "servers" to listOf(
                                    mapOf(
                                        "address" to host,
                                        "port" to port,
                                        "method" to method,
                                        "password" to password
                                    )
                                )
                            )
                        )
                    )
                )
            )

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
