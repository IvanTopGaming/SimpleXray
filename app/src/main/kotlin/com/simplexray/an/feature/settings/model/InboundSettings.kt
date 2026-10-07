package com.simplexray.an.feature.settings.model

import com.simplexray.an.prefs.Preferences
import java.net.InetAddress

data class InboundSettings(
    val socksAddress: String = "127.0.0.1",
    val socksPort: Int = 10808,
    val socksUsername: String = "",
    val socksPassword: String = "",
    val httpProxyEnabled: Boolean = true,
    val httpPort: Int = 10809,
    val socksUdpEnabled: Boolean = true,
    val allowLanAccess: Boolean = false,
    val ipv6: Boolean = false,
) {
    val effectiveListenAddress: String
        get() = if (allowLanAccess || isLoopback(socksAddress)) socksAddress else "127.0.0.1"

    val connectAddress: String
        get() {
            val address = effectiveListenAddress
            return if (numericAddress(address)?.isAnyLocalAddress == true) {
                if (address.contains(':')) "::1" else "127.0.0.1"
            } else address
        }

    fun validate() {
        require(numericAddress(socksAddress) != null) { "Укажи корректный IPv4 или IPv6 адрес" }
        require(socksPort in 1..65535 && httpPort in 1..65535) { "Порт должен быть от 1 до 65535" }
        require(!httpProxyEnabled || socksPort != httpPort) {
            "Порты SOCKS и HTTP должны отличаться"
        }
    }

    companion object {
        fun fromPreferences(prefs: Preferences) =
            InboundSettings(
                socksAddress = prefs.socksAddress,
                socksPort = prefs.socksPort,
                socksUsername = prefs.socksUsername,
                socksPassword = prefs.socksPassword,
                httpProxyEnabled = prefs.httpProxyEnabled,
                httpPort = prefs.httpPort,
                socksUdpEnabled = prefs.socksUdpEnabled,
                allowLanAccess = prefs.allowLanAccess,
                ipv6 = prefs.ipv6,
            )

        fun isLoopback(address: String): Boolean =
            numericAddress(address)?.isLoopbackAddress == true

        private fun numericAddress(address: String): InetAddress? {
            if (!address.contains(':') && !address.matches(Regex("[0-9]+(?:\\.[0-9]+){3}")))
                return null
            return runCatching { InetAddress.getByName(address) }.getOrNull()
        }
    }
}
