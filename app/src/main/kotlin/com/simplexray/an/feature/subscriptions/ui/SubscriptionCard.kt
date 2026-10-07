package com.simplexray.an.feature.subscriptions.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.feature.subscriptions.data.subscriptionBytes
import com.simplexray.an.feature.subscriptions.data.subscriptionExpiryText
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.feature.subscriptions.state.SubscriptionSyncState
import com.simplexray.an.ui.components.LabIcon
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun SubscriptionCard(
    sub: Subscription,
    syncState: SubscriptionSyncState?,
    onSync: () -> Unit,
    onEdit: () -> Unit,
    onServers: () -> Unit,
    onDelete: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val usage = sub.usage
    val used = usage?.used
    val total = usage?.total
    val progress = usage?.progress
    val exhausted = usage?.remaining == 0L
    val error = sub.lastError
    var nowSeconds by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(usage?.expire) {
        while (true) {
            nowSeconds = System.currentTimeMillis() / 1000
            delay(60000)
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(15.dp)) {
            Column(
                Modifier.fillMaxWidth().clickable(onClick = onServers).semantics {
                    contentDescription = "Открыть серверы: ${sub.displayName}"
                }
            ) {
                Text(
                    sub.displayName,
                    style =
                        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${sub.files.size} серверов →",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (syncState?.syncing == true) "Обновление…"
                else if (sub.lastUpdated == 0L) "Ещё не обновлялась"
                else
                    "Обновлено " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(sub.lastUpdated)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (error != null && syncState?.syncing != true) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Использовано",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    "${used?.let(::subscriptionBytes) ?: "—"} / ${if (total == 0L) "∞" else total?.let(::subscriptionBytes) ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exhausted) colors.error else colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth()
                    .height(5.dp)
                    .background(colors.outlineVariant, RoundedCornerShape(6.dp))
                    .semantics {
                        contentDescription = "Использование трафика"
                        if (progress != null)
                            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    }
            ) {
                if (progress != null)
                    Box(
                        Modifier.fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(
                                if (exhausted) colors.error else colors.primary,
                                RoundedCornerShape(6.dp),
                            )
                    )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                when {
                    exhausted -> "Лимит трафика исчерпан"
                    total == 0L -> "Безлимитный трафик"
                    usage?.remaining != null -> "Осталось ${subscriptionBytes(usage.remaining!!)}"
                    else -> "Нет данных о трафике"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (exhausted) colors.error else colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                subscriptionExpiryText(usage?.expire, nowSeconds),
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (usage?.expire != null && usage.expire > 0 && usage.expire <= nowSeconds)
                        colors.error
                    else colors.onSurfaceVariant,
            )
            val actionStyle =
                MaterialTheme.typography.labelLarge.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                )
            val textMeasurer = rememberTextMeasurer()
            val actionsWidth =
                with(LocalDensity.current) {
                    listOf("Обновить", "Настроить", "Удалить")
                        .map {
                            (textMeasurer
                                    .measure(it, actionStyle, softWrap = false)
                                    .size
                                    .width
                                    .toDp() + 26.dp)
                                .coerceAtLeast(64.dp)
                        }
                        .fold(0.dp) { total, width -> total + width } + 12.dp
                }
            val actions: @Composable (Modifier, Boolean) -> Unit = { modifier, showLabels ->
                TextButton(
                    onClick = onSync,
                    enabled = syncState?.syncing != true,
                    modifier =
                        modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Обновить подписку"
                        },
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    LabIcon("refresh", modifier = Modifier.size(18.dp))
                    if (showLabels) {
                        Spacer(Modifier.width(8.dp))
                        Text("Обновить", style = actionStyle, maxLines = 1)
                    }
                }
                TextButton(
                    onClick = onEdit,
                    enabled = syncState?.syncing != true,
                    modifier =
                        modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Изменить ссылку подписки"
                        },
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    LabIcon("settings", modifier = Modifier.size(18.dp))
                    if (showLabels) {
                        Spacer(Modifier.width(8.dp))
                        Text("Настроить", style = actionStyle, maxLines = 1)
                    }
                }
                TextButton(
                    onClick = onDelete,
                    modifier =
                        modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Удалить подписку"
                        },
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    LabIcon(
                        "delete",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    if (showLabels) {
                        Spacer(Modifier.width(8.dp))
                        Text("Удалить", color = colors.error, style = actionStyle, maxLines = 1)
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                val showLabels = maxWidth >= actionsWidth
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    actions(if (showLabels) Modifier else Modifier.weight(1f), showLabels)
                }
            }
        }
    }
}
