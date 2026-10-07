package com.simplexray.an.feature.routing.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlock
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.settings.LabSettingDescription

@Composable
internal fun RoutingBlockCard(
    block: RoutingBlock,
    index: Int,
    legacyOrder: Boolean = false,
    total: Int = 3,
    serverAvailable: Boolean = true,
    onChooseServer: () -> Unit = {},
    onRemove: () -> Unit = {},
    onSave: (String) -> String?,
    onMove: (Int) -> Unit,
) {
    var text by rememberSaveable(block.id) { mutableStateOf(block.text) }
    var error by rememberSaveable(block.id) { mutableStateOf<String?>(null) }
    var confirmRemove by rememberSaveable(block.id) { mutableStateOf(false) }
    val title = if (block.isServer) "Через сервер" else block.target.blockTitle()
    val example =
        when (block.target) {
            RouteTarget.DIRECT -> "geoip:ru\ngeosite:category-ru\nip:192.168.1.0/24"
            RouteTarget.PROXY -> "geosite:google\nsuffix:youtube.com\nsuffix:openai.com"
            RouteTarget.BLOCK ->
                "geosite:category-ads-all\nsuffix:doubleclick.net\nsuffix:googlesyndication.com"
        }
    Surface(
        Modifier.fillMaxWidth().testTag("routing-block-${block.id}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(
                    onClick = { onMove(-1) },
                    enabled = index > 0,
                    modifier = Modifier.semantics { contentDescription = "Поднять блок $title" },
                ) {
                    LabIcon("arrow", modifier = Modifier.rotate(-90f))
                }
                IconButton(
                    onClick = { onMove(1) },
                    enabled = index < total - 1,
                    modifier = Modifier.semantics { contentDescription = "Опустить блок $title" },
                ) {
                    LabIcon("arrow", modifier = Modifier.rotate(90f))
                }
            }
            if (block.isServer) {
                Text(
                    block.server?.name ?: "Сервер не выбран",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabButton(
                        if (block.server == null || !serverAvailable) "Выбрать сервер"
                        else "Заменить сервер",
                        onChooseServer,
                        Modifier.weight(1f).testTag("routing-choose-${block.id}"),
                        icon = "servers",
                        compact = true,
                    )
                    IconButton(
                        onClick = {
                            if (text.isNotBlank() || block.text.isNotBlank()) confirmRemove = true
                            else onRemove()
                        },
                        modifier = Modifier.testTag("routing-delete-${block.id}"),
                    ) {
                        LabIcon("delete", contentDescription = "Удалить серверный блок")
                    }
                }
            }
            LabSettingDescription(
                if (block.isServer && block.server == null) "Выбери сервер для правил этого блока."
                else if (block.isServer && !serverAvailable)
                    "Сервер недоступен. Выбери замену для этого блока."
                else if (block.isServer) "Домены и сети, которые идут через выбранный сервер."
                else if (legacyOrder) "Редактирование сгруппирует старые правила по блокам."
                else
                    when (block.target) {
                        RouteTarget.DIRECT -> "Домены и сети, которые идут без прокси."
                        RouteTarget.PROXY -> "Домены и сети, которые идут через прокси."
                        RouteTarget.BLOCK -> "Домены и сети, к которым запрещён доступ."
                    }
            )
            OutlinedTextField(
                value = text,
                onValueChange = { updated ->
                    text = updated
                    error = onSave(updated)
                },
                modifier =
                    Modifier.fillMaxWidth().testTag("routing-text-${block.id}").semantics {
                        contentDescription = "Правила $title"
                    },
                minLines = 4,
                maxLines = 8,
                textStyle =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    ),
                keyboardOptions =
                    KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                placeholder = {
                    Text(example, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                },
                shape = RoundedCornerShape(9.dp),
                isError = error != null,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        errorContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (confirmRemove)
        LabDialog(
            "Удалить серверный блок?",
            { confirmRemove = false },
            actions = {
                LabButton("Отмена", { confirmRemove = false }, Modifier.weight(1f), icon = "close")
                LabButton(
                    "Удалить",
                    {
                        confirmRemove = false
                        onRemove()
                    },
                    Modifier.weight(1f),
                    danger = true,
                    icon = "delete",
                )
            },
        ) {
            Text("Правила этого блока будут удалены.")
        }
}
