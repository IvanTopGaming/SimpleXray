package com.simplexray.an.feature.dashboard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.feature.dashboard.model.CoreStatsState
import java.util.Locale

@Composable
internal fun DashboardSpeeds(
    connected: Boolean,
    download: Long,
    upload: Long,
    modifier: Modifier,
    trafficStatsEnabled: Boolean = true,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomeMetric(
            "↓ Загрузка",
            if (trafficStatsEnabled) speedLabel(if (connected) download else 0) else "Выключено",
            Modifier.weight(1f),
        )
        HomeMetric(
            "↑ Отдача",
            if (trafficStatsEnabled) speedLabel(if (connected) upload else 0) else "Выключено",
            Modifier.weight(1f),
        )
    }
}

@Composable
internal fun DashboardTotals(connected: Boolean, stats: CoreStatsState, modifier: Modifier) {
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val time = if (connected) stats.uptime.toLong() else 0L
            listOf(
                    ("↓ " +
                        if (stats.trafficStatsEnabled)
                            totalLabel(if (connected) stats.downlink else 0)
                        else "Выключено") to "Получено",
                    ("↑ " +
                        if (stats.trafficStatsEnabled)
                            totalLabel(if (connected) stats.uplink else 0)
                        else "Выключено") to "Отправлено",
                    "%02d:%02d".format(time / 60, time % 60) to "Время",
                )
                .forEach { (value, label) ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            value,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 15.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            label,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 14.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
        }
    }
}

private fun speedLabel(bytes: Long): String =
    if (bytes < 1024 * 1024) "${bytes / 1024} КБ/с"
    else String.format(Locale.forLanguageTag("ru"), "%.1f МБ/с", bytes / 1048576.0)

private fun totalLabel(bytes: Long): String =
    String.format(Locale.forLanguageTag("ru"), "%.1f", bytes / 1048576.0).removeSuffix(",0") + " МБ"

@Composable
private fun HomeMetric(label: String, value: String, modifier: Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontSize = 22.sp,
                    lineHeight = 27.sp,
                    letterSpacing = (-.5).sp,
                ),
            textAlign = TextAlign.Center,
        )
    }
}
