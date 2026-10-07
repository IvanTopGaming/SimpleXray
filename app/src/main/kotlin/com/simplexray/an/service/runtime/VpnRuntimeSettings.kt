package com.simplexray.an.service.runtime

import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.core.network.socks.socksAuthenticationEnabled
import com.simplexray.an.prefs.Preferences

internal class VpnRuntimeSettings(prefs: Preferences, config: AppConfig) {
    val disableVpn = prefs.disableVpn
    val socksAddress = config.inboundSettings.connectAddress
    val socksPort = config.socksPort
    val socksUsername = config.socksUsername
    val socksPassword = config.socksPassword
    val ipv6 = config.ipv6
    val ipv4 = prefs.ipv4
    val dnsIpv4 = prefs.dnsIpv4
    val dnsIpv6 = prefs.dnsIpv6
    val bypassLan = prefs.bypassLan
    val httpProxyEnabled = config.inboundSettings.httpProxyEnabled
    val advertiseHttpProxy =
        httpProxyEnabled && !socksAuthenticationEnabled(socksUsername, socksPassword)
    val httpPort = config.inboundSettings.httpPort
    val apps = prefs.apps?.toSet()
    val appRoutingMode = prefs.appRoutingMode
    val tunnelMtu = 8500
    val tunnelIpv4Address = prefs.tunnelIpv4Address
    val tunnelIpv4Prefix = prefs.tunnelIpv4Prefix
    val tunnelIpv6Address = prefs.tunnelIpv6Address
    val tunnelIpv6Prefix = prefs.tunnelIpv6Prefix
    val taskStackSize = prefs.taskStackSize
    val udpInTcp = prefs.udpInTcp
}
