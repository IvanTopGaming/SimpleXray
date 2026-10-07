package com.simplexray.an.feature.dashboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.ui.components.LabIcon

@Composable
internal fun DashboardConnectionControl(
    connected: Boolean,
    ready: Boolean,
    preparation: String?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
            if (connected && ready) {
                Surface(
                    Modifier.requiredSize(128.dp),
                    shape = CircleShape,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    border =
                        BorderStroke(
                            2.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = .45f),
                        ),
                ) {}
            }
            if (!ready) {
                Surface(
                    Modifier.requiredSize(128.dp),
                    shape = CircleShape,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    border =
                        BorderStroke(
                            2.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = .55f),
                        ),
                ) {}
            }
            Surface(
                onClick = onClick,
                enabled = ready || preparation != null,
                shape = CircleShape,
                color =
                    if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainer,
                border =
                    BorderStroke(
                        1.dp,
                        if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                modifier =
                    Modifier.size(112.dp).semantics {
                        contentDescription =
                            if (preparation != null) "Отменить подключение"
                            else if (connected) "Отключить" else "Подключить"
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LabIcon(
                        "power",
                        modifier = Modifier.size(36.dp),
                        tint =
                            if (connected) MaterialTheme.colorScheme.background
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(
            if (preparation != null) {
                preparation.orEmpty()
            } else if (!ready) {
                if (connected) "Отключение…" else "Подключение…"
            } else if (connected) "Подключено" else "Отключено",
            style =
                MaterialTheme.typography.titleMedium.copy(
                    fontSize = 19.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Bold,
                ),
        )
        Spacer(Modifier.height(7.dp))
    }
}
