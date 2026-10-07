package com.simplexray.an.feature.servers.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.feature.servers.model.ServerCheckState
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabField
import com.simplexray.an.ui.components.LabIcon

@Composable
internal fun ConfigListControls(
    query: String,
    onQueryChange: (String) -> Unit,
    sort: String,
    onSortChange: (String) -> Unit,
    grouping: ServerGrouping,
    onGroupChange: (ServerGrouping) -> Unit,
    checks: ServerCheckState,
    hasVisibleServers: Boolean,
    onCheckServers: () -> Unit,
) {
    Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabField(
                query,
                onValueChange = { onQueryChange(it) },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Поиск серверов" },
                placeholder = "Сервер, страна или подписка",
            )
            if (query.isNotEmpty()) {
                Surface(
                    onClick = { onQueryChange("") },
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier =
                        Modifier.size(48.dp).semantics { contentDescription = "Очистить поиск" },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ServerFilter("Группировка", grouping.title, Modifier.weight(1f)) { close ->
                DropdownMenuItem(
                    text = { Text("По подпискам") },
                    leadingIcon = { LabIcon("subs", modifier = Modifier.size(18.dp)) },
                    onClick = {
                        onGroupChange(ServerGrouping.SUBSCRIPTION)
                        close()
                    },
                )
                DropdownMenuItem(
                    text = { Text("По странам") },
                    leadingIcon = { LabIcon("globe", modifier = Modifier.size(18.dp)) },
                    onClick = {
                        onGroupChange(ServerGrouping.COUNTRY)
                        close()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Без группировки") },
                    leadingIcon = { LabIcon("servers", modifier = Modifier.size(18.dp)) },
                    onClick = {
                        onGroupChange(ServerGrouping.NONE)
                        close()
                    },
                )
            }
            ServerFilter(
                "Сортировка",
                when (sort) {
                    "name" -> "По имени"
                    "latency" -> "По задержке"
                    else -> "Исходный порядок"
                },
                Modifier.weight(1f),
            ) { close ->
                DropdownMenuItem(
                    text = { Text("По имени") },
                    leadingIcon = { LabIcon("sort", modifier = Modifier.size(18.dp)) },
                    onClick = {
                        onSortChange("name")
                        close()
                    },
                )
                DropdownMenuItem(
                    text = { Text("По задержке") },
                    leadingIcon = { LabIcon("activity", modifier = Modifier.size(18.dp)) },
                    onClick = {
                        onSortChange("latency")
                        close()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Исходный порядок") },
                    leadingIcon = { LabIcon("servers", modifier = Modifier.size(18.dp)) },
                    onClick = {
                        onSortChange("original")
                        close()
                    },
                )
            }
        }
        LabButton(
            if (checks.running) "Отменить проверку" else "Проверить доступность",
            onClick = { onCheckServers() },
            enabled = checks.running || hasVisibleServers,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            icon = if (checks.running) "close" else "activity",
        )
        val summary =
            "${checks.available} доступно · ${checks.unavailable} не отвечает" +
                if (checks.skipped > 0) " · ${checks.skipped} изменено" else ""
        Text(
            when {
                checks.running -> "Проверка ${checks.completed} из ${checks.total}…"
                checks.cancelled ->
                    "Проверка отменена · ${checks.completed} из ${checks.total}\n$summary"
                checks.total > 0 -> "✓ $summary"
                else -> "Проверка ещё не запускалась"
            },
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.2.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
