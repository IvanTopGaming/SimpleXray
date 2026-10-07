package com.simplexray.an.feature.routing.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.RightAlignedMenu

@Composable
fun RoutingPresetMenu(onImport: () -> Unit, onExport: () -> Unit, busy: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        LabButton(
            "Пресет",
            onClick = { expanded = !expanded },
            enabled = !busy,
            icon = "route",
            compact = true,
        )
        RightAlignedMenu(expanded && !busy, onDismissRequest = { expanded = false }) {
            listOf(
                    Triple("Импорт из буфера", "import", onImport),
                    Triple("Экспорт в буфер", "export", onExport),
                )
                .forEach { (label, icon, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = { LabIcon(icon, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            expanded = false
                            action()
                        },
                    )
                }
        }
    }
}

@Composable
fun RoutingPresetPreviewDialog(
    preset: RoutingPreset,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    busy: Boolean = false,
    error: String? = null,
) {
    val dismiss = { if (!busy) onDismiss() }
    val routing = preset.routing
    LabDialog(
        title = "Заменить настройки роутинга?",
        onDismiss = dismiss,
        error = error,
        actions = {
            LabButton("Отмена", dismiss, Modifier.weight(1f), enabled = !busy, icon = "close")
            LabButton(
                if (busy) "Сохранение…" else "Заменить",
                onClick = { if (!busy) onApply() },
                modifier = Modifier.weight(1f),
                primary = true,
                enabled = !busy,
                icon = "check",
            )
        },
    ) {
        Text(
            "Пресет полностью заменит текущие правила, их порядок, настройки роутинга и обе ссылки на базы. Несохранённый текст правил тоже будет заменён.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Правил: ${routing.rules.size}. Включено: ${routing.rules.count { it.enabled }}.",
            style = MaterialTheme.typography.bodySmall,
        )
        routing.blocks?.let { blocks ->
            Text(
                "Порядок: " +
                    blocks.joinToString(" → ") {
                        if (it.isServer) "Через сервер" else it.target.presetLabel()
                    },
                style = MaterialTheme.typography.bodySmall,
            )
        }
            ?: Text(
                "Порядок правил сохранится как в пресете.",
                style = MaterialTheme.typography.bodySmall,
            )
        if (routing.blocks.orEmpty().any { it.isServer })
            Text(
                "После импорта выбери серверы для блоков «Через сервер».",
                style = MaterialTheme.typography.bodySmall,
            )
        Text(
            "По умолчанию: ${routing.defaultRoute.presetLabel()}",
            style = MaterialTheme.typography.bodySmall,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("База IP-адресов (GeoIP)", style = MaterialTheme.typography.labelLarge)
            Text(
                preset.geoipUrl,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text("База доменов (GeoSite)", style = MaterialTheme.typography.labelLarge)
            Text(
                preset.geositeUrl,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "Изменения вступят в силу при следующем подключении. Отмена оставит текущие настройки без изменений.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun RouteTarget.presetLabel(): String =
    when (this) {
        RouteTarget.BLOCK -> "Блокировать"
        RouteTarget.DIRECT -> "Напрямую"
        RouteTarget.PROXY -> "Прокси"
    }
