package com.simplexray.an.feature.dashboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.feature.servers.model.ServerCheckResult
import com.simplexray.an.feature.servers.model.ServerDetails
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.ServerLatencyBadge
import java.io.File

@Composable
internal fun DashboardServerCard(
    selected: File?,
    details: ServerDetails,
    ownerName: String?,
    result: ServerCheckResult?,
    checking: Boolean,
    onServers: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(
            "ВЫБРАННЫЙ СЕРВЕР",
            style =
                MaterialTheme.typography.bodySmall.copy(
                    letterSpacing = .8.sp,
                    lineHeight = 14.sp,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            onClick = onServers,
            shape = RoundedCornerShape(15.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LabIcon("servers", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        selected?.nameWithoutExtension ?: "Выбрать сервер",
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                lineHeight = 17.sp,
                            ),
                    )
                    Text(
                        listOfNotNull(
                                details.country ?: ownerName ?: "Ручной сервер",
                                details.protocol,
                            )
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 14.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ServerLatencyBadge(
                    result,
                    checking,
                    Modifier.testTag("latency-home"),
                )
                LabIcon("arrow", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
