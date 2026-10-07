package com.simplexray.an.core.config.ownership

import com.google.gson.JsonParser
import com.simplexray.an.core.config.routing.RoutingServerTags

internal object RoutingServerOutbounds {
    fun add(source: String, profiles: Map<String, String>): String {
        if (profiles.isEmpty()) return source
        val root = JsonParser.parseString(source).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        profiles.forEach { (blockId, profile) ->
            val graph =
                ServerProfileGraph(
                    ServerProfileGraph.objectValue(ServerProfileGraph.parse(profile))
                )
            val selected =
                graph.roots.singleOrNull { graph.tag(it) == "proxy" } ?: graph.roots.singleOrNull()
            require(selected != null) { "Не удалось выбрать выход сервера в блоке роутинга" }
            val tag = RoutingServerTags.outbound(blockId)
            graph
                .profile(selected, tag, "${tag}_chain_", runtime = true)
                .getAsJsonArray("outbounds")
                .forEach { outbounds.add(it) }
        }
        return root.toString()
    }
}
