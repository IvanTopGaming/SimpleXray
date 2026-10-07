package com.simplexray.an.core.config.kernel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.core.config.kernel.KernelOutboundGraph.effectiveAccounts
import com.simplexray.an.core.config.kernel.KernelOutboundGraph.objectOrCreate
import com.simplexray.an.core.config.kernel.KernelOutboundGraph.streamConfigs
import com.simplexray.an.core.config.kernel.KernelOutboundGraph.strings
import com.simplexray.an.core.config.routing.RoutingServerTags
import com.simplexray.an.feature.kernel.model.KernelSettings
import com.simplexray.an.feature.kernel.model.ServerDomainStrategy
import com.simplexray.an.feature.kernel.model.SniffProtocol
import com.simplexray.an.feature.kernel.model.TcpCongestion

object KernelConfigCompiler {
    fun compile(source: String, settings: KernelSettings): String {
        settings.validate()
        val root = JsonParser.parseString(source).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        val owned =
            outbounds
                .map { it.asJsonObject }
                .filter {
                    val tag = it["tag"]?.asString
                    tag == "proxy" ||
                        tag?.startsWith("__sx_chain_") == true ||
                        RoutingServerTags.isOwned(tag)
                }
        val primary = owned.singleOrNull { it["tag"]?.asString == "proxy" }
        require(primary != null) { "Не удалось выбрать основной прокси для настроек ядра" }
        owned
            .filter {
                it["tag"]?.asString == "proxy" || RoutingServerTags.isPrimary(it["tag"]?.asString)
            }
            .forEach { primary ->
                if (settings.muxEnabled) {
                    require(primary["protocol"]?.asString != "wireguard") {
                        "WireGuard не поддерживает Mux. Отключи Mux для этого сервера."
                    }
                    val vision =
                        primary["protocol"]?.asString == "vless" &&
                            effectiveAccounts(primary).any {
                                it["flow"]
                                    ?.takeUnless { flow -> flow.isJsonNull }
                                    ?.asString
                                    ?.startsWith("xtls-rprx-vision") == true
                            }
                    require(!vision || settings.muxConcurrency < 0) {
                        "XTLS Vision несовместим с TCP Mux. Установи TCP Mux = -1 для XUDP или отключи Mux."
                    }
                }
                primary.add(
                    "mux",
                    JsonObject().apply {
                        addProperty("enabled", settings.muxEnabled)
                        addProperty("concurrency", settings.muxConcurrency)
                        addProperty("xudpConcurrency", settings.xudpConcurrency)
                        addProperty("xudpProxyUDP443", settings.udp443.configValue)
                    },
                )
            }
        val fake = root.has("fakedns")
        root
            .getAsJsonArray("inbounds")
            .map { it.asJsonObject }
            .filter { it["protocol"]?.asString in listOf("socks", "http") }
            .forEach { inbound ->
                inbound.add(
                    "sniffing",
                    JsonObject().apply {
                        addProperty("enabled", fake || settings.sniffingEnabled)
                        addProperty("routeOnly", settings.sniffingRouteOnly)
                        addProperty(
                            "metadataOnly",
                            fake &&
                                (!settings.sniffingEnabled || settings.sniffingProtocols.isEmpty()),
                        )
                        add(
                            "destOverride",
                            strings(
                                buildList {
                                    if (fake) add("fakedns")
                                    if (settings.sniffingEnabled)
                                        SniffProtocol.entries
                                            .filter { it in settings.sniffingProtocols }
                                            .forEach { add(it.configValue) }
                                }
                            ),
                        )
                    },
                )
            }
        owned.forEach { outbound ->
            outbound.objectOrCreate("streamSettings")
            streamConfigs(outbound).forEach { stream ->
                val socket = stream.objectOrCreate("sockopt")
                for (field in
                    listOf(
                        "domainStrategy",
                        "tcpFastOpen",
                        "tcpKeepAliveInterval",
                        "tcpUserTimeout",
                        "tcpCongestion",
                    )) socket.remove(field)
                socket.addProperty("domainStrategy", settings.serverDomainStrategy.configValue)
                if (settings.tcpFastOpen) socket.addProperty("tcpFastOpen", true)
                if (settings.tcpKeepAliveInterval != 0)
                    socket.addProperty("tcpKeepAliveInterval", settings.tcpKeepAliveInterval)
                if (settings.tcpUserTimeout != 0)
                    socket.addProperty("tcpUserTimeout", settings.tcpUserTimeout)
                if (settings.tcpCongestion != TcpCongestion.SYSTEM)
                    socket.addProperty("tcpCongestion", settings.tcpCongestion.configValue)
            }
        }
        policy(root, owned, settings)
        if (settings.serverDomainStrategy != ServerDomainStrategy.AS_IS)
            KernelBootstrapDns.apply(root, owned)
        return root.toString()
    }

    private fun policy(root: JsonObject, owned: List<JsonObject>, settings: KernelSettings) {
        val overrides =
            linkedMapOf(
                "handshake" to settings.handshake,
                "connIdle" to settings.connectionIdle,
                "uplinkOnly" to settings.uplinkOnly,
                "downlinkOnly" to settings.downlinkOnly,
                "bufferSize" to settings.bufferSize,
            )
        if (overrides.values.all { it == null } && !root.has("policy")) return
        val policy = root.objectOrCreate("policy")
        val levels = policy.objectOrCreate("levels")
        val referenced = linkedSetOf("0")
        owned.forEach { outbound ->
            effectiveAccounts(outbound).forEach { account ->
                account["level"]
                    ?.takeUnless { it.isJsonNull }
                    ?.let {
                        val level = it.asString.toLongOrNull()
                        require(level != null && level in 0L..4294967295L) {
                            "Некорректный уровень пользователя сервера"
                        }
                        referenced.add(level.toString())
                    }
            }
        }
        referenced.forEach { level ->
            val entry = levels.objectOrCreate(level)
            overrides.forEach { (key, value) ->
                entry.remove(key)
                if (value != null) entry.addProperty(key, value)
            }
            if (entry.size() == 0) levels.remove(level)
        }
        if (levels.size() == 0) policy.remove("levels")
        if (policy.size() == 0) root.remove("policy")
    }
}
