package com.simplexray.an.feature.dashboard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.core.geodata.GeodataProgress
import com.simplexray.an.feature.dashboard.model.ConnectionPreparation
import com.simplexray.an.ui.components.LabIcon
import java.util.Locale

@Composable
internal fun DashboardConnectionControl(
    connected: Boolean,
    ready: Boolean,
    preparation: ConnectionPreparation?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val download = preparation?.download
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
            if (download != null) {
                DownloadRing(download)
            } else if (!ready) {
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
                preparation.message
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
        AnimatedVisibility(
            download != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                download?.let(::downloadLabel).orEmpty(),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(7.dp))
    }
}

@Composable
private fun DownloadRing(download: GeodataProgress) {
    val modifier = Modifier.requiredSize(128.dp)
    val color = MaterialTheme.colorScheme.primary
    val track = color.copy(alpha = .15f)
    val fraction = download.fraction
    if (fraction == null) {
        CircularProgressIndicator(
            modifier,
            color = color,
            strokeWidth = 3.dp,
            trackColor = track,
            strokeCap = StrokeCap.Round,
        )
    } else {
        key(download.filename) {
            val progress by animateFloatAsState(fraction, label = "geodata")
            CircularProgressIndicator(
                progress = { progress },
                modifier = modifier,
                color = color,
                strokeWidth = 3.dp,
                trackColor = track,
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

private fun downloadLabel(download: GeodataProgress): String {
    val loaded = megabytes(download.bytes)
    return when {
        download.total > 0L -> "${download.filename} · $loaded из ${megabytes(download.total)} МБ"
        download.bytes > 0L -> "${download.filename} · $loaded МБ"
        else -> download.filename
    }
}

private fun megabytes(bytes: Long): String =
    String.format(Locale.forLanguageTag("ru"), "%.1f", bytes / 1048576.0)
