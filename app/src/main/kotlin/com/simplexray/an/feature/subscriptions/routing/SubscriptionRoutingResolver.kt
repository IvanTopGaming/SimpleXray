package com.simplexray.an.feature.subscriptions.routing

import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.core.config.routing.RoutingLayer
import com.simplexray.an.core.geodata.GeodataFiles
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.routing.server.RoutingServerCatalog
import com.simplexray.an.feature.subscriptions.data.files.SubscriptionConfigFiles
import com.simplexray.an.feature.subscriptions.model.Subscription
import java.io.File

data class ResolvedSubscriptionRouting(
    val layer: RoutingLayer,
    val serverSources: Map<String, String>,
    val unresolved: List<String>,
    val geodata: Map<String, String>,
)

object SubscriptionRoutingResolver {
    fun forSource(
        filesDir: File,
        sourceName: String,
        subscriptions: List<Subscription>,
        global: RoutingPreset,
    ): ResolvedSubscriptionRouting? {
        val subscription = subscriptions.firstOrNull { sourceName in it.files } ?: return null
        if (subscription.routingDisabled) return null
        val routing = SubscriptionRoutingStore(filesDir).read(subscription.id) ?: return null
        return resolve(routing, subscription, subscriptions, serverFiles(filesDir), global)
    }

    fun unresolved(
        filesDir: File,
        subscription: Subscription,
        subscriptions: List<Subscription>,
        routing: SubscriptionRouting,
    ): List<String> {
        val files = serverFiles(filesDir)
        return routing.blocks
            .filter { block ->
                block.isServer &&
                    block.server?.let { source(it, subscription, subscriptions, files) } == null
            }
            .map { it.server.orEmpty() }
    }

    fun resolve(
        routing: SubscriptionRouting,
        subscription: Subscription,
        subscriptions: List<Subscription>,
        files: List<File>,
        global: RoutingPreset,
    ): ResolvedSubscriptionRouting {
        routing.validate()
        val taken =
            (global.routing.rules.mapNotNull { it.serverBlockId } +
                    global.routing.blocks.orEmpty().map { it.id })
                .toSet()
        fun id(index: Int) = generateSequence("sub_$index") { "${it}_" }.first { it !in taken }
        val sources = mutableMapOf<String, String>()
        val unresolved = mutableListOf<String>()
        val rules =
            routing.blocks.flatMapIndexed { index, block ->
                val rules = routing.rules(index, block, ::id)
                if (!block.isServer) return@flatMapIndexed rules
                val content = block.server?.let { source(it, subscription, subscriptions, files) }
                if (content == null) {
                    unresolved.add(block.server.orEmpty())
                    rules.map { it.copy(serverBlockId = null) }
                } else {
                    sources[id(index)] = content
                    rules
                }
            }
        val geodata = mutableMapOf<String, String>()
        fun file(url: String?, base: String, globalUrl: String): String {
            if (url == null || url == globalUrl) return base
            val name = GeodataFiles.forSubscription(subscription.id, base) ?: return base
            geodata[name] = url
            return name
        }
        val layer =
            RoutingLayer(
                rules,
                file(routing.geoipUrl, GeodataFiles.GEOIP, global.geoipUrl),
                file(routing.geositeUrl, GeodataFiles.GEOSITE, global.geositeUrl),
            )
        return ResolvedSubscriptionRouting(layer, sources, unresolved, geodata)
    }

    private fun source(
        name: String,
        subscription: Subscription,
        subscriptions: List<Subscription>,
        files: List<File>,
    ): String? {
        val wanted = normalize(name)
        val otherOwned =
            subscriptions.filter { it.id != subscription.id }.flatMap { it.files }.toSet()
        val match =
            files
                .filter {
                    it.isFile &&
                        it.name in subscription.files &&
                        it.name !in otherOwned &&
                        RoutingServerCatalog.serverName(subscription, it.name) == wanted
                }
                .singleOrNull() ?: return null
        return try {
            match.readText().also { OwnedConfig.serverProfile(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalize(name: String): String =
        SubscriptionConfigFiles.sanitize(name).replace(Regex(" \\(([2-9]|[1-9][0-9]+)\\)$"), "")

    private fun serverFiles(filesDir: File): List<File> =
        filesDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty().toList()
}
