package com.simplexray.an.feature.routing.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.routing.model.DomainStrategy
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.server.RoutingServerOption
import com.simplexray.an.feature.routing.state.RoutingEditor
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.settings.LabSettingToggle
import com.simplexray.an.ui.components.settings.LabSettingValue

@Composable
internal fun RoutingSettingsSection(
    editor: RoutingEditor,
    serverOptions: List<RoutingServerOption> = emptyList(),
) {
    val state by editor.state.collectAsState()
    val draft = state.draft
    val blocks = remember(draft) { draft.blocks ?: RoutingBlocks.fromRules(draft.rules) }
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    var serverChoice by rememberSaveable { mutableStateOf<String?>(null) }
    var reset by rememberSaveable { mutableStateOf(false) }
    var blockError by remember { mutableStateOf(false) }
    val available = !state.corrupt

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (available) {
            blocks.forEachIndexed { index, block ->
                key(block.id, state.revision) {
                    RoutingBlockCard(
                        block,
                        index,
                        total = blocks.size,
                        serverAvailable =
                            block.server?.let { isRoutingServerAvailable(it, serverOptions) }
                                ?: false,
                        onChooseServer = { serverChoice = block.id },
                        onRemove = {
                            blockError = false
                            editor.removeBlock(block.id)
                        },
                        legacyOrder =
                            index == 0 &&
                                draft.blocks == null &&
                                RoutingBlocks.hasInterleavedTargets(draft.rules),
                        onSave = { text ->
                            blockError = true
                            if (editor.saveBlock(block.id, text)) null
                            else editor.state.value.error ?: "Не удалось сохранить блок"
                        },
                        onMove = { direction ->
                            blockError = false
                            val current = editor.state.value.draft
                            val order = current.blocks ?: RoutingBlocks.fromRules(current.rules)
                            editor.moveBlock(
                                block.id,
                                order.indexOfFirst { it.id == block.id } + direction,
                            )
                        },
                    )
                }
            }
            LabButton(
                "Добавить блок",
                { serverChoice = "" },
                Modifier.fillMaxWidth().testTag("routing-add-server"),
                enabled = blocks.size < 32,
                icon = "add",
            )
        }
        LabSettingValue(
            "Остальной трафик",
            draft.defaultRoute.label(),
            "Маршрут для адресов, не попавших в правила.",
            available,
        ) {
            choice = "default"
        }
        LabSettingToggle(
            "Обход локальной сети",
            "Локальные адреса идут напрямую, до остальных правил.",
            draft.bypassLan,
            { enabled ->
                blockError = false
                editor.edit { it.copy(bypassLan = enabled) }
            },
            available,
        )
        LabSettingValue(
            "Разрешение доменов",
            draft.domainStrategy.label(),
            "Когда искать IP для правил. FakeIP + IP/LAN: IPOnDemand.",
            available,
        ) {
            choice = "strategy"
        }
        if (!blockError || state.corrupt)
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        if (state.corrupt)
            LabButton(
                "Сбросить роутинг",
                { reset = true },
                Modifier.fillMaxWidth(),
                danger = true,
                icon = "reset",
            )
    }

    serverChoice?.let { id ->
        RoutingServerPicker(
            serverOptions,
            onSelect = { server ->
                blockError = false
                val saved =
                    if (id.isEmpty()) editor.addServerBlock(server)
                    else editor.selectBlockServer(id, server)
                if (saved) serverChoice = null
            },
            onDismiss = { serverChoice = null },
        )
    }
    choice?.let { current ->
        RoutingChoice(
            if (current == "default") "Остальной трафик" else "Разрешение доменов",
            if (current == "default") RouteTarget.entries.map { it.label() }
            else DomainStrategy.entries.map { it.label() },
            if (current == "default") draft.defaultRoute.ordinal else draft.domainStrategy.ordinal,
            { index ->
                blockError = false
                editor.edit {
                    if (current == "default") it.copy(defaultRoute = RouteTarget.entries[index])
                    else it.copy(domainStrategy = DomainStrategy.entries[index])
                }
                choice = null
            },
            { choice = null },
        )
    }
    if (reset)
        LabDialog(
            "Сбросить роутинг?",
            { reset = false },
            actions = {
                LabButton("Отмена", { reset = false }, Modifier.weight(1f), icon = "close")
                LabButton(
                    "Сбросить",
                    {
                        editor.reset()
                        reset = false
                    },
                    Modifier.weight(1f),
                    danger = true,
                    icon = "reset",
                )
            },
        ) {
            Text(
                "Правила будут сброшены. По умолчанию трафик идёт через прокси, локальная сеть — напрямую."
            )
        }
}
