package com.simplexray.an.core.config

import com.simplexray.an.core.config.dns.DnsConfigCompiler
import com.simplexray.an.core.config.inbound.InboundConfigCompiler
import com.simplexray.an.core.config.kernel.KernelConfigCompiler
import com.simplexray.an.core.config.logging.LoggingConfigCompiler
import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.core.config.ownership.RoutingServerOutbounds
import com.simplexray.an.core.config.profile.ProfileGeodata
import com.simplexray.an.core.config.profile.ProfileOverrides
import com.simplexray.an.core.config.routing.RoutingCompiler
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.kernel.model.KernelSettings
import com.simplexray.an.feature.logs.model.LogSettings
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.settings.model.InboundSettings
import com.simplexray.an.prefs.Preferences

class AppConfig(prefs: Preferences) {
    val inboundSettings = InboundSettings.fromPreferences(prefs)
    val socksHost = inboundSettings.effectiveListenAddress
    val socksPort = inboundSettings.socksPort
    val socksUsername = inboundSettings.socksUsername
    val socksPassword = inboundSettings.socksPassword
    val ipv6 = inboundSettings.ipv6
    val logSettings = LogSettings.decode(prefs.logSettingsJson)
    private val profileOverridesJson = prefs.profileOverridesJson
    private val dnsSettings = DnsSettings.decode(prefs.dnsSettingsJson, prefs.dnsIpv4)
    private val kernelSettings = KernelSettings.decode(prefs.kernelSettingsJson)

    fun build(source: String): String = build(source, null)

    private fun build(
        source: String,
        routing: RoutingSettings?,
        serverSources: Map<String, String> = emptyMap(),
    ): String =
        LoggingConfigCompiler.compile(
            KernelConfigCompiler.compile(
                DnsConfigCompiler.compile(
                    InboundConfigCompiler.configure(
                        RoutingServerOutbounds.add(
                            OwnedConfig.build(
                                source,
                                socksHost,
                                socksPort,
                                socksUsername,
                                socksPassword,
                            ),
                            serverSources,
                        ),
                        inboundSettings,
                    ),
                    dnsSettings,
                    ipv6,
                    routing,
                ),
                kernelSettings,
            ),
            logSettings,
        )

    fun buildProfile(
        source: String,
        routing: RoutingSettings,
        overrides: ProfileOverrides = ProfileOverrides.decode(profileOverridesJson),
        serverSources: Map<String, String> = emptyMap(),
    ): String {
        routing.validate()
        val additional =
            routing.rules
                .filter { it.enabled }
                .mapNotNull { it.serverBlockId }
                .distinct()
                .associateWith { id ->
                    requireNotNull(serverSources[id]) {
                        "Выбери доступный сервер для каждого блока роутинга"
                    }
                }
        val compiled =
            RoutingCompiler.compile(
                build(source, routing.takeUnless { overrides.hasCustomDnsRouting }, additional),
                routing,
            )
        val result =
            InboundConfigCompiler.applyTrafficPolicy(overrides.applyTo(compiled), inboundSettings)
        ProfileGeodata.requiredFiles(result)
        return result
    }
}
