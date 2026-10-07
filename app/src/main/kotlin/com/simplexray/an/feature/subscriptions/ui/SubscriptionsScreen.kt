package com.simplexray.an.feature.subscriptions.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.labGap
import com.simplexray.an.ui.components.labHorizontalPadding

@Composable
fun SubscriptionsScreen(
    mainViewModel: MainViewModel,
    onAdd: () -> Unit,
    onServers: (String) -> Unit,
) {
    val subscriptions by mainViewModel.subscriptions.collectAsState()
    val sync by mainViewModel.subscriptionSync.collectAsState()
    var deleting by remember { mutableStateOf<Subscription?>(null) }
    var editing by remember { mutableStateOf<Subscription?>(null) }
    val outline = MaterialTheme.colorScheme.outlineVariant
    LazyColumn(
        contentPadding =
            PaddingValues(
                start = labHorizontalPadding(),
                end = labHorizontalPadding(),
                top = labGap(),
                bottom = 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (subscriptions.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 160.dp)
                        .drawBehind {
                            drawRoundRect(
                                outline,
                                cornerRadius = CornerRadius(15.dp.toPx()),
                                style =
                                    Stroke(
                                        1.dp.toPx(),
                                        pathEffect =
                                            PathEffect.dashPathEffect(
                                                floatArrayOf(4.dp.toPx(), 4.dp.toPx())
                                            ),
                                    ),
                            )
                        }
                        .padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Подписок пока нет.\nДобавьте подписку.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(subscriptions, key = { it.id }) { sub ->
            SubscriptionCard(
                sub,
                sync[sub.id],
                { mainViewModel.syncSubscription(sub.id) },
                { editing = sub },
                { onServers(sub.id) },
                { deleting = sub },
            )
        }
    }
    editing?.let { sub ->
        AddSubscriptionDialog(
            initialUrl = sub.url,
            editing = true,
            onDismiss = { editing = null },
            onConfirm = { _, url ->
                mainViewModel.updateSubscriptionUrl(sub.id, url)
                editing = null
            },
        )
    }
    deleting?.let { sub ->
        LabDialog(
            title = "Удалить подписку?",
            onDismiss = { deleting = null },
            actions = {
                LabButton("Отмена", { deleting = null }, Modifier.weight(1f), icon = "close")
                LabButton(
                    "Удалить",
                    {
                        mainViewModel.deleteSubscription(sub.id)
                        deleting = null
                    },
                    Modifier.weight(1f),
                    danger = true,
                    icon = "delete",
                )
            },
        ) {
            Text(
                "Все серверы этой подписки также будут удалены.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
