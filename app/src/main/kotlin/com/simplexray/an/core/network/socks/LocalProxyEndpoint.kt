package com.simplexray.an.core.network.socks

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.feature.settings.model.InboundSettings
import com.simplexray.an.prefs.Preferences
import java.net.InetAddress
import java.net.PasswordAuthentication

class LocalProxyEndpoint(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    init {
        require(host.isNotBlank() && port in 1..65535) {
            "Некорректные параметры локального прокси"
        }
    }

    fun encode(): String =
        JsonObject()
            .apply {
                addProperty("host", host)
                addProperty("port", port)
                addProperty("user", username)
                addProperty("pass", password)
            }
            .toString()

    fun authentication(host: String?, port: Int, protocol: String?): PasswordAuthentication? {
        if (port != this.port || !protocol.equals("SOCKS5", ignoreCase = true)) return null
        if (
            host != this.host &&
                (numericAddress(host) == null || numericAddress(host) != numericAddress(this.host))
        )
            return null
        if (!socksAuthenticationEnabled(username, password)) return null
        return PasswordAuthentication(username, password.toCharArray())
    }

    companion object {
        fun active(prefs: Preferences): LocalProxyEndpoint {
            val saved = prefs.activeProxySettingsJson
            if (saved != null) {
                return try {
                    val root = JsonParser.parseString(saved).asJsonObject
                    fun text(name: String): String {
                        val field = root[name]
                        require(
                            field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString
                        )
                        return field.asString
                    }
                    LocalProxyEndpoint(
                        text("host"),
                        root["port"].asString.toInt(),
                        text("user"),
                        text("pass"),
                    )
                } catch (_: Exception) {
                    throw IllegalArgumentException(
                        "Не удалось прочитать параметры активного локального прокси"
                    )
                }
            }
            val settings = InboundSettings.fromPreferences(prefs)
            return LocalProxyEndpoint(
                settings.connectAddress,
                settings.socksPort,
                settings.socksUsername,
                settings.socksPassword,
            )
        }

        private fun numericAddress(host: String?): InetAddress? {
            if (
                host == null ||
                    (!host.contains(':') && !host.matches(Regex("[0-9]+(?:\\.[0-9]+){3}")))
            )
                return null
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }
    }
}
