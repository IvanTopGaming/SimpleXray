package com.simplexray.an.core.config.dns

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.core.config.routing.RoutingServerTags
import com.simplexray.an.feature.dns.model.DnsQueryStrategy
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingSettings

object DnsConfigCompiler {
    const val GUARD_RULE = "__sx_fake_pool_guard"
    val fakePools = listOf("198.19.0.0/16", "fd00:198:19::/64")

    fun compile(
        source: String,
        settings: DnsSettings,
        ipv6: Boolean,
        routing: RoutingSettings? = null,
    ): String {
        settings.validate()
        val root = JsonParser.parseString(source).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        val clients =
            root
                .getAsJsonArray("inbounds")
                .map { it.asJsonObject }
                .filter { it["protocol"]?.asString in listOf("socks", "http") }
        require(clients.isNotEmpty()) { "Для DNS нужен SOCKS или HTTP вход" }
        val used =
            (outbounds.mapNotNull { it.asJsonObject["tag"]?.asString } +
                    root.getAsJsonArray("inbounds").mapNotNull { it.asJsonObject["tag"]?.asString })
                .toMutableSet()
        fun unique(base: String): String {
            var tag = base
            var index = 1
            while (!used.add(tag)) tag = "${base}_${index++}"
            return tag
        }
        val clientTags =
            clients.map {
                it["tag"]?.asString
                    ?: unique("__sx_client").also { tag -> it.addProperty("tag", tag) }
            }
        val splitRules = routing?.let(SplitDnsRules::from).orEmpty()
        val hasDirect = routing?.defaultRoute == RouteTarget.DIRECT || splitRules.any { it.direct }
        val split = hasDirect || splitRules.any { it.serverBlockId != null }
        val directDnsTag = if (hasDirect) unique("__sx_direct_dns") else null
        val directTransportTag = if (hasDirect) unique("__sx_direct_dns_transport") else null
        val dnsTag = unique("__sx_dns")
        val transportTag = unique("__sx_dns_transport")
        val dnsTags = mutableListOf(dnsTag)
        val directDnsTags = mutableListOf<String>()
        val serverDnsTags = linkedMapOf<String, MutableList<String>>()
        val serverTransports = linkedMapOf<String, String>()
        val dnsOutboundTag = unique("__sx_dns_out")
        val guardTag = unique("__sx_fake_block")
        val candidates =
            outbounds
                .map { it.asJsonObject }
                .filter {
                    it["protocol"]?.asString !in
                        listOf(null, "freedom", "blackhole", "dns", "loopback") &&
                        !RoutingServerTags.isOwned(it["tag"]?.asString)
                }
        val proxy =
            candidates.singleOrNull { it["tag"]?.asString == "proxy" } ?: candidates.singleOrNull()
        require(settings.route != RouteTarget.PROXY || proxy != null) {
            "Не удалось выбрать прокси для DNS"
        }
        val servers = JsonArray()
        val hosts = JsonObject()
        val strategy =
            if (!ipv6 && settings.queryStrategy == DnsQueryStrategy.AUTO) "UseIPv4"
            else settings.queryStrategy.configValue
        if (settings.fakeIpEnabled) {
            if (split)
                servers.add(
                    JsonObject().apply {
                        addProperty("address", "fakedns")
                        addProperty("tag", unique("__sx_fake_dns"))
                        add("domains", strings(listOf("regexp:.*")))
                    }
                )
            else servers.add("fakedns")
        }
        fun resolver(
            value: String,
            bootstrap: String,
            tag: String = dnsTag,
            domains: List<String> = emptyList(),
            final: Boolean = false,
        ) {
            val resolver = DnsSettings.resolver(value, bootstrap)
            resolver.hostname?.let { hostname ->
                require(!hosts.has(hostname) || hosts[hostname].asString == bootstrap) {
                    "Для одного DNS-домена нужны одинаковые bootstrap IP"
                }
                hosts.addProperty(hostname, bootstrap)
            }
            val resolverTag = if (domains.isEmpty()) tag else unique("${tag}_group")
            when {
                tag == directDnsTag -> directDnsTags.add(resolverTag)
                tag in serverDnsTags -> serverDnsTags.getValue(tag).add(resolverTag)
                else -> dnsTags.add(resolverTag)
            }
            servers.add(
                JsonObject().apply {
                    addProperty("address", resolver.address)
                    addProperty("port", resolver.port)
                    addProperty("tag", resolverTag)
                    if (domains.isNotEmpty()) {
                        add("domains", strings(domains))
                        addProperty("skipFallback", true)
                    }
                    if (final) addProperty("finalQuery", true)
                    addProperty("queryStrategy", strategy)
                    addProperty("timeoutMs", 3000)
                }
            )
        }
        fun resolvers(
            direct: Boolean,
            domains: List<String> = emptyList(),
            serverBlockId: String? = null,
        ) {
            val tag =
                if (serverBlockId != null) {
                    val outboundTag = RoutingServerTags.outbound(serverBlockId)
                    require(outbounds.any { it.asJsonObject["tag"]?.asString == outboundTag }) {
                        "Не найден выход сервера для DNS блока"
                    }
                    unique("__sx_server_dns").also {
                        serverDnsTags[it] = mutableListOf()
                        serverTransports[it] = outboundTag
                    }
                } else dnsTag
            if (direct)
                resolver(
                    settings.directDns,
                    settings.directBootstrap,
                    directDnsTag!!,
                    domains,
                    true,
                )
            else {
                resolver(
                    settings.primaryDns,
                    settings.primaryBootstrap,
                    tag,
                    domains,
                    split && !settings.fallbackEnabled,
                )
                if (settings.fallbackEnabled)
                    resolver(settings.fallbackDns, settings.fallbackBootstrap, tag, domains, split)
            }
        }
        if (split) splitRules.forEach { resolvers(it.direct, it.domains, it.serverBlockId) }
        resolvers(split && routing?.defaultRoute == RouteTarget.DIRECT)
        root.add(
            "dns",
            JsonObject().apply {
                addProperty("tag", dnsTag)
                addProperty("queryStrategy", strategy)
                addProperty("disableCache", !settings.cacheEnabled)
                addProperty("enableParallelQuery", false)
                add("hosts", hosts)
                add("servers", servers)
            },
        )
        root.remove("fakedns")
        if (settings.fakeIpEnabled)
            root.add(
                "fakedns",
                JsonArray().apply {
                    fakePools.take(if (ipv6) 2 else 1).forEach { pool ->
                        add(
                            JsonObject().apply {
                                addProperty("ipPool", pool)
                                addProperty("poolSize", 32768)
                            }
                        )
                    }
                },
            )
        outbounds.add(
            transport(
                transportTag,
                if (settings.route == RouteTarget.PROXY) proxy!!["tag"].asString else null,
            )
        )
        if (hasDirect) outbounds.add(transport(directTransportTag!!))
        val serverTransportTags =
            serverTransports.mapValues { (_, proxyTag) ->
                unique("__sx_server_dns_transport").also { tag ->
                    outbounds.add(transport(tag, proxyTag))
                }
            }
        outbounds.add(
            JsonObject().apply {
                addProperty("tag", dnsOutboundTag)
                addProperty("protocol", "dns")
                add(
                    "settings",
                    JsonObject().apply {
                        add(
                            "rules",
                            JsonArray().apply {
                                add(
                                    JsonObject().apply {
                                        addProperty("action", "hijack")
                                        addProperty("qType", "1,28")
                                    }
                                )
                                add(
                                    JsonObject().apply {
                                        addProperty("action", "return")
                                        addProperty("rCode", 5)
                                    }
                                )
                            },
                        )
                    },
                )
            }
        )
        outbounds.add(
            JsonObject().apply {
                addProperty("tag", guardTag)
                addProperty("protocol", "blackhole")
                add("settings", JsonObject())
            }
        )
        root.add(
            "routing",
            JsonObject().apply {
                add(
                    "rules",
                    JsonArray().apply {
                        serverTransportTags.forEach { (dnsTag, outboundTag) ->
                            add(
                                JsonObject().apply {
                                    addProperty("type", "field")
                                    add("inboundTag", strings(serverDnsTags.getValue(dnsTag)))
                                    addProperty("outboundTag", outboundTag)
                                }
                            )
                        }
                        if (hasDirect)
                            add(
                                JsonObject().apply {
                                    addProperty("type", "field")
                                    add("inboundTag", strings(directDnsTags.distinct()))
                                    addProperty("outboundTag", directTransportTag)
                                }
                            )
                        add(
                            JsonObject().apply {
                                addProperty("type", "field")
                                add("inboundTag", strings(clientTags))
                                addProperty("network", "tcp,udp")
                                addProperty("port", "53")
                                addProperty("outboundTag", dnsOutboundTag)
                            }
                        )
                        add(
                            JsonObject().apply {
                                addProperty("type", "field")
                                add("inboundTag", strings(dnsTags.distinct()))
                                addProperty("outboundTag", transportTag)
                            }
                        )
                        add(
                            JsonObject().apply {
                                addProperty("type", "field")
                                addProperty("ruleTag", GUARD_RULE)
                                add("inboundTag", strings(clientTags))
                                add("ip", strings(fakePools))
                                addProperty("outboundTag", guardTag)
                            }
                        )
                    },
                )
            },
        )
        clients.forEach { client ->
            client.add(
                "sniffing",
                JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("routeOnly", true)
                    add(
                        "destOverride",
                        strings(
                            if (settings.fakeIpEnabled) listOf("fakedns", "http", "tls", "quic")
                            else listOf("http", "tls", "quic")
                        ),
                    )
                },
            )
        }
        return root.toString()
    }

    private fun transport(tag: String, proxy: String? = null): JsonObject =
        JsonObject().apply {
            addProperty("tag", tag)
            addProperty("protocol", "freedom")
            add("settings", JsonObject())
            add(
                "streamSettings",
                JsonObject().apply {
                    add(
                        "sockopt",
                        JsonObject().apply {
                            addProperty("domainStrategy", "ForceIP")
                            if (proxy != null) addProperty("dialerProxy", proxy)
                        },
                    )
                },
            )
        }

    private fun strings(values: List<String>) = JsonArray().apply { values.forEach { add(it) } }
}
