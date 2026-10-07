package com.simplexray.an.service.runtime

import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import com.simplexray.an.core.network.socks.socksAuthenticationEnabled
import com.simplexray.an.feature.apps.model.AppRoutingPolicy

internal object VpnTunnelConfig {
    fun builder(service: VpnService, prefs: VpnRuntimeSettings): VpnService.Builder =
        service.Builder().apply {
            setBlocking(false)
            setMtu(prefs.tunnelMtu)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setMetered(false)
            }

            if (prefs.bypassLan) {
                addRoute("10.0.0.0", 8)
                addRoute("172.16.0.0", 12)
                addRoute("192.168.0.0", 16)
            }
            if (prefs.advertiseHttpProxy) {
                setHttpProxy(ProxyInfo.buildDirectProxy(prefs.socksAddress, prefs.httpPort))
            }
            if (prefs.ipv4) {
                addAddress(prefs.tunnelIpv4Address, prefs.tunnelIpv4Prefix)
                addRoute("0.0.0.0", 0)
                prefs.dnsIpv4.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
            }
            if (prefs.ipv6) {
                addAddress(prefs.tunnelIpv6Address, prefs.tunnelIpv6Prefix)
                addRoute("::", 0)
                prefs.dnsIpv6.takeIf { it.isNotEmpty() }?.also { addDnsServer(it) }
            }

            AppRoutingPolicy.configure(this, prefs.appRoutingMode, prefs.apps)
        }

    fun configuration(prefs: VpnRuntimeSettings): String {
        var tproxyConf =
            """misc:
  task-stack-size: ${prefs.taskStackSize}
tunnel:
  mtu: ${prefs.tunnelMtu}
"""
        tproxyConf +=
            """socks5:
  port: ${prefs.socksPort}
  address: '${prefs.socksAddress}'
  udp: '${if (prefs.udpInTcp) "tcp" else "udp"}'
"""
        if (socksAuthenticationEnabled(prefs.socksUsername, prefs.socksPassword)) {
            tproxyConf += "  username: '" + prefs.socksUsername + "'\n"
            tproxyConf += "  password: '" + prefs.socksPassword + "'\n"
        }
        return tproxyConf
    }
}
