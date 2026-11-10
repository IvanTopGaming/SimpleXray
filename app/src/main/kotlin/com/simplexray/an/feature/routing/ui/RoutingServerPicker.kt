package com.simplexray.an.feature.routing.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.simplexray.an.feature.routing.model.RoutingServerRef
import com.simplexray.an.feature.routing.server.RoutingServerOption
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.LabIcon

@Composable
internal fun RoutingServerPicker(
    options: List<RoutingServerOption>,
    onSelect: (RoutingServerRef) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches =
        remember(options, query) {
            options.filter {
                it.reference.name.contains(query.trim(), ignoreCase = true) ||
                    it.group.contains(query.trim(), ignoreCase = true)
            }
        }
    LabDialog(
        "Сервер для блока",
        onDismiss,
        actions = { LabButton("Отмена", onDismiss, Modifier.fillMaxWidth(), icon = "close") },
    ) {
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth().testTag("routing-server-search"),
            singleLine = true,
            label = { Text("Поиск сервера") },
            leadingIcon = { LabIcon("servers") },
        )
        if (matches.isEmpty())
            Text(
                if (options.isEmpty()) "Нет доступных серверов. Добавь сервер или подписку."
                else "Серверы не найдены."
            )
        matches
            .groupBy { it.group }
            .forEach { (group, servers) ->
                Text(group, style = MaterialTheme.typography.titleSmall)
                servers.forEach { option ->
                    LabButton(
                        option.reference.name,
                        { onSelect(option.reference) },
                        Modifier.fillMaxWidth()
                            .testTag("routing-server-${option.reference.fileName}"),
                        icon = "servers",
                    )
                }
            }
    }
}

internal fun isRoutingServerAvailable(
    reference: RoutingServerRef,
    options: List<RoutingServerOption>,
): Boolean {
    if (reference.subscriptionId == null)
        return options.any {
            it.reference.subscriptionId == null && it.reference.fileName == reference.fileName
        }
    val owned =
        options
            .filter { it.reference.subscriptionId == reference.subscriptionId }
            .ifEmpty {
                val key = reference.subscriptionKey ?: return false
                options
                    .filter { it.reference.subscriptionKey == key }
                    .takeIf { matches ->
                        matches.map { it.reference.subscriptionId }.distinct().size == 1
                    }
                    .orEmpty()
            }
    val named = owned.filter { it.reference.name == reference.name }
    if (!reference.matchFingerprint && named.size == 1) return true
    if (reference.fingerprint.isEmpty()) return false
    fun pinned(candidates: List<RoutingServerOption>) =
        candidates.count {
            it.reference.fingerprint.equals(reference.fingerprint, ignoreCase = true)
        } == 1
    return pinned(named) || pinned(owned)
}
