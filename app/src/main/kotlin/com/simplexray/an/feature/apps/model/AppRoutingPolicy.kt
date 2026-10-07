package com.simplexray.an.feature.apps.model

import android.content.pm.PackageManager
import android.net.VpnService
import com.simplexray.an.BuildConfig

object AppRoutingPolicy {
    fun configure(builder: VpnService.Builder, mode: AppRoutingMode, selected: Set<String?>?) {
        val packages =
            selected.orEmpty().filterNotNull().filterNot { it == BuildConfig.APPLICATION_ID }
        when (mode) {
            AppRoutingMode.ALL -> builder.addDisallowedApplication(BuildConfig.APPLICATION_ID)
            AppRoutingMode.EXCLUDE -> {
                packages.forEach { name ->
                    try {
                        builder.addDisallowedApplication(name)
                    } catch (_: PackageManager.NameNotFoundException) {}
                }
                builder.addDisallowedApplication(BuildConfig.APPLICATION_ID)
            }
            AppRoutingMode.INCLUDE -> {
                var included = 0
                packages.forEach { name ->
                    try {
                        builder.addAllowedApplication(name)
                        included++
                    } catch (_: PackageManager.NameNotFoundException) {}
                }
                require(included > 0) { "Выберите хотя бы одно установленное приложение для VPN." }
            }
        }
    }
}
