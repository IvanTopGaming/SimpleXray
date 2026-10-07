package com.simplexray.an.feature.settings.state

import com.simplexray.an.ui.theme.ThemeMode

data class SwitchStates(
    val ipv6Enabled: Boolean,
    val httpProxyEnabled: Boolean,
    val bypassLanEnabled: Boolean,
    val disableVpn: Boolean,
    val themeMode: ThemeMode,
    val socksUdpEnabled: Boolean = true,
    val allowLanAccess: Boolean = false,
    val autoStartEnabled: Boolean = false,
)
