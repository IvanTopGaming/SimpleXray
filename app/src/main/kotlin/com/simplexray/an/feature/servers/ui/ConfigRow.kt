package com.simplexray.an.feature.servers.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.feature.servers.model.ServerCheckResult
import com.simplexray.an.feature.servers.model.ServerDetails
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.ServerLatencyBadge
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConfigRow(
    file: File,
    isSelected: Boolean,
    readOnly: Boolean,
    details: ServerDetails,
    owner: String,
    result: ServerCheckResult?,
    checking: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("server-row-${file.name}"),
        shape = RoundedCornerShape(13.dp),
        color = if (isSelected) colors.surfaceContainer else colors.background,
        border = BorderStroke(1.dp, if (isSelected) colors.primary else colors.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(1.dp).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .semantics { selected = isSelected }
                    .combinedClickable(
                        onClick = onSelect,
                        onLongClick = if (readOnly) null else onDelete,
                        onLongClickLabel = if (readOnly) null else "Удалить сервер",
                        role = Role.Button,
                    )
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    details.code,
                    Modifier.background(colors.surfaceContainer, RoundedCornerShape(9.dp))
                        .padding(6.dp),
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = colors.onSurfaceVariant,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        file.nameWithoutExtension,
                        style = MaterialTheme.typography.titleSmall.copy(lineHeight = 17.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(details.country ?: owner, details.protocol)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 15.sp),
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ServerLatencyBadge(result, checking, Modifier.testTag("latency-${file.name}"))
            }
            Box(
                Modifier.width(48.dp)
                    .fillMaxHeight()
                    .clickable(role = Role.Button, onClick = onEdit)
                    .semantics {
                        contentDescription =
                            (if (readOnly) "Просмотреть " else "Редактировать ") +
                                file.nameWithoutExtension
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.align(Alignment.CenterStart)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colors.outlineVariant)
                        .testTag("server-action-divider-${file.name}")
                )
                LabIcon(
                    if (readOnly) "info" else "edit",
                    modifier = Modifier.size(18.dp),
                    tint = colors.primary,
                )
            }
        }
    }
}
