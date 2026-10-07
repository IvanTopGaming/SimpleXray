package com.simplexray.an.feature.servers.ui

import com.simplexray.an.feature.servers.model.ServerCheckResult
import com.simplexray.an.feature.servers.model.ServerDetails
import java.io.File

internal enum class ServerGrouping(val title: String) {
    SUBSCRIPTION("По подпискам"),
    COUNTRY("По странам"),
    NONE("Без группировки"),
}

internal data class ConfigGroup(val key: String, val title: String, val files: List<File>) {
    fun isCollapsed(collapsed: List<String>, query: String): Boolean =
        key != "none" && key in collapsed && query.isBlank()
}

internal fun configGroups(
    files: List<File>,
    owners: Map<String, String>,
    names: Map<String, String>,
    details: Map<File, ServerDetails>,
    grouping: ServerGrouping = ServerGrouping.SUBSCRIPTION,
    query: String = "",
    sort: String = "name",
    results: Map<String, ServerCheckResult> = emptyMap(),
    subscriptionFilter: String? = null,
): List<ConfigGroup> {
    val visible =
        files
            .filter { file ->
                (subscriptionFilter == null || owners[file.name] == subscriptionFilter) &&
                    listOf(
                            file.nameWithoutExtension,
                            names[owners[file.name]].orEmpty(),
                            details[file]?.country.orEmpty(),
                        )
                        .joinToString(" ")
                        .contains(query.trim(), ignoreCase = true)
            }
            .let { filtered ->
                when (sort) {
                    "name" -> filtered.sortedBy { it.name.lowercase() }
                    "latency" ->
                        filtered.sortedWith(
                            compareBy<File> {
                                    results[it.absolutePath]?.latencyMs ?: Long.MAX_VALUE
                                }
                                .thenBy { it.name.lowercase() }
                        )
                    else -> filtered
                }
            }
    return when (grouping) {
        ServerGrouping.NONE -> listOf(ConfigGroup("none", "", visible))
        ServerGrouping.SUBSCRIPTION ->
            visible
                .groupBy { owners[it.name]?.takeIf(names::containsKey) }
                .map { (id, servers) ->
                    ConfigGroup(
                        id?.let { "subscription:$it" } ?: "manual",
                        names[id] ?: "Ручные серверы",
                        servers,
                    )
                }
        ServerGrouping.COUNTRY ->
            visible
                .groupBy { file ->
                    details[file]
                        ?.takeIf { !it.country.isNullOrBlank() }
                        ?.let { it.code.takeUnless { code -> code == "—" } ?: it.country }
                }
                .map { (code, servers) ->
                    ConfigGroup(
                        code?.let { "country:$it" } ?: "country:unknown",
                        details[servers.first()]?.country?.takeIf(String::isNotBlank)
                            ?: "Без страны",
                        servers,
                    )
                }
    }
}
