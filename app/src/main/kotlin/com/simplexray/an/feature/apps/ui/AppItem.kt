package com.simplexray.an.feature.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.feature.apps.state.Package

@Composable
fun AppItem(
    pkg: Package,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    first: Boolean = true,
    last: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .testTag("app-${pkg.packageName}")
            .drawBehind {
                val r = 16.dp.toPx()
                val stroke = 1.dp.toPx()
                val left =
                    Path().apply {
                        if (first) {
                            moveTo(r, stroke / 2)
                            quadraticBezierTo(stroke / 2, stroke / 2, stroke / 2, r)
                        } else moveTo(stroke / 2, 0f)
                        lineTo(stroke / 2, size.height - if (last) r else 0f)
                        if (last)
                            quadraticBezierTo(
                                stroke / 2,
                                size.height - stroke / 2,
                                r,
                                size.height - stroke / 2,
                            )
                    }
                val right =
                    Path().apply {
                        if (first) {
                            moveTo(size.width - r, stroke / 2)
                            quadraticBezierTo(
                                size.width - stroke / 2,
                                stroke / 2,
                                size.width - stroke / 2,
                                r,
                            )
                        } else moveTo(size.width - stroke / 2, 0f)
                        lineTo(size.width - stroke / 2, size.height - if (last) r else 0f)
                        if (last)
                            quadraticBezierTo(
                                size.width - stroke / 2,
                                size.height - stroke / 2,
                                size.width - r,
                                size.height - stroke / 2,
                            )
                    }
                drawPath(left, colors.outlineVariant, style = Stroke(stroke))
                drawPath(right, colors.outlineVariant, style = Stroke(stroke))
                if (first)
                    drawLine(
                        colors.outlineVariant,
                        Offset(r, stroke / 2),
                        Offset(size.width - r, stroke / 2),
                        stroke,
                    )
                val inset = if (last) r else 14.dp.toPx()
                drawLine(
                    colors.outlineVariant,
                    Offset(inset, size.height - stroke / 2),
                    Offset(size.width - inset, size.height - stroke / 2),
                    stroke,
                )
            }
            .toggleable(
                value = pkg.selected,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                pkg.label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface.copy(alpha = if (enabled) 1f else .45f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pkg.packageName,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else .45f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val track = if (pkg.selected) colors.primary else colors.outlineVariant
        Box(
            Modifier.size(36.dp, 22.dp)
                .background(track.copy(alpha = if (enabled) 1f else .45f), CircleShape)
                .border(
                    1.dp,
                    colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else .45f),
                    CircleShape,
                )
                .padding(4.dp),
            contentAlignment = if (pkg.selected) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier.size(14.dp)
                    .background(
                        if (pkg.selected) colors.background else colors.onSurfaceVariant,
                        CircleShape,
                    )
            )
        }
    }
}
