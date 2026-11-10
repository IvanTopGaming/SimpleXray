package com.simplexray.an.feature.routing.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.feature.subscriptions.routing.SubscriptionRoutingBlock
import com.simplexray.an.feature.subscriptions.routing.SubscriptionRoutingView
import com.simplexray.an.ui.components.settings.LabSettingDescription
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SubscriptionRoutingTab(sub: Subscription, view: SubscriptionRoutingView) {
    val colors = MaterialTheme.colorScheme
    val routing = view.routing
    Column(
        Modifier.fillMaxWidth().testTag("subscription-routing-${sub.id}"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LabSettingDescription(
            "Правила провайдера действуют только для серверов этой подписки, после глобальных." +
                (routing.updatedAt.takeIf { it > 0 }?.let {
                    " Обновлены " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(it)) +
                        "."
                } ?: "")
        )
        if (sub.routingDisabled)
            Notice("Приём выключен в настройках подписки — эти правила сейчас не применяются.")
        sub.routingError?.let { Notice(it) }
        if (!sub.routingDisabled)
            view.unresolved.forEach { name ->
                Notice(
                    if (name.isEmpty()) "Сервер блока не указан — правила идут через активный сервер."
                    else "Сервер «$name» не найден — правила идут через активный сервер."
                )
            }
        routing.blocks.forEach { RoutingBlockView(it) }
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Геобазы подписки", style = MaterialTheme.typography.titleMedium)
                GeodataRow("GeoIP", routing.geoipUrl)
                GeodataRow("GeoSite", routing.geositeUrl)
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun GeodataRow(title: String, url: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        SelectionContainer {
            Text(
                url ?: "Глобальная база",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoutingBlockView(block: SubscriptionRoutingBlock) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (block.isServer) block.server?.let { "Через сервер «$it»" } ?: "Через сервер"
                else block.target.blockTitle(),
                style = MaterialTheme.typography.titleMedium,
            )
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(9.dp),
                color = colors.surfaceContainer,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                SelectionContainer {
                    Text(
                        block.text.ifBlank { "Нет правил" },
                        Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = if (block.text.isBlank()) colors.onSurfaceVariant else colors.onSurface,
                    )
                }
            }
        }
    }
}
