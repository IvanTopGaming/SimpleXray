package com.simplexray.an.feature.routing.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.routing.model.DomainStrategy
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog

@Composable
internal fun RoutingChoice(
    title: String,
    choices: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    LabDialog(
        title,
        onDismiss,
        actions = { LabButton("Отмена", onDismiss, Modifier.fillMaxWidth(), icon = "close") },
    ) {
        choices.forEachIndexed { index, label ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onSelect(index) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected == index, onClick = null)
                Text(
                    label,
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

internal fun RouteTarget.blockTitle(): String = label()

internal fun RouteTarget.label(): String =
    when (this) {
        RouteTarget.PROXY -> "Прокси"
        RouteTarget.DIRECT -> "Напрямую"
        RouteTarget.BLOCK -> "Блокировать"
    }

internal fun DomainStrategy.label(): String =
    when (this) {
        DomainStrategy.AS_IS -> "AsIs · без DNS-запросов для правил"
        DomainStrategy.IP_ON_DEMAND -> "IPOnDemand · разрешать для IP-правил"
    }
