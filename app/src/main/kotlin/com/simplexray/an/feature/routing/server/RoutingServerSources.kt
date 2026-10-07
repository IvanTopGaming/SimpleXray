package com.simplexray.an.feature.routing.server

import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.subscriptions.model.Subscription
import java.io.File

object RoutingServerSources {
    fun read(
        settings: RoutingSettings,
        directory: File,
        subscriptions: List<Subscription>,
    ): Map<String, String> {
        settings.validate()
        val ids = settings.rules.filter { it.enabled }.mapNotNull { it.serverBlockId }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val files =
            directory
                .listFiles { file -> file.isFile && file.name.endsWith(".json") }
                .orEmpty()
                .toList()
        return ids.associateWith { id ->
            val block = settings.blocks?.singleOrNull { it.id == id }
            val reference =
                requireNotNull(block?.server) {
                    "Выбери сервер для блока «Через сервер» в роутинге"
                }
            RoutingServerCatalog.read(reference, files, subscriptions)
        }
    }
}
