package com.simplexray.an.service.runtime

import com.simplexray.an.core.runtime.assets.CoreAssetSnapshot

internal data class PreparedRuntimeConfig(
    val content: String,
    val address: String,
    val port: Int,
    val assets: CoreAssetSnapshot,
    val vpn: VpnRuntimeSettings,
    val trafficStatsEnabled: Boolean,
    val serverName: String,
    val subscriptionDomain: String?,
)
