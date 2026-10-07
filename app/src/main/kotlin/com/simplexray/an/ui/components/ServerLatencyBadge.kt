package com.simplexray.an.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.servers.model.ServerCheckResult

@Composable
fun ServerLatencyBadge(
    result: ServerCheckResult?,
    checking: Boolean,
    modifier: Modifier = Modifier,
) {
    val latency = result?.latencyMs
    val color =
        when {
            checking || latency == null -> MaterialTheme.colorScheme.onSurfaceVariant
            latency < 100 -> MaterialTheme.colorScheme.primary
            latency <= 250 -> Color(0xFFAC7A20)
            else -> MaterialTheme.colorScheme.error
        }
    val text =
        when {
            checking -> "…"
            latency != null -> "$latency мс"
            result != null -> "×"
            else -> "—"
        }
    val description =
        when {
            checking -> "Проверяется"
            latency != null -> "Задержка последней проверки: $latency мс"
            result != null -> "Не отвечает: ${result.error.orEmpty()}"
            else -> "Ещё не проверен"
        }
    Row(
        modifier.widthIn(max = 84.dp).semantics(mergeDescendants = true) {
            contentDescription = description
        },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
