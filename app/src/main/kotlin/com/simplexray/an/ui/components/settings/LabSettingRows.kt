package com.simplexray.an.ui.components.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.ui.components.LabIcon

@Composable
internal fun LabSettingToggle(
    title: String,
    help: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
) {
    val available = enabled && onCheckedChange != null
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(
                    checked,
                    enabled = available,
                    role = Role.Switch,
                    onValueChange = { onCheckedChange?.invoke(it) },
                )
                .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            LabSettingDescription(help)
        }
        Box(
            Modifier.size(width = 36.dp, height = 22.dp)
                .alpha(if (available) 1f else .45f)
                .background(
                    if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                )
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier.size(14.dp)
                    .background(
                        if (checked) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        CircleShape,
                    )
            )
        }
    }
}

@Composable
internal fun LabSettingValue(
    title: String,
    value: String,
    help: String,
    enabled: Boolean = true,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            LabSettingDescription(help)
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border =
                BorderStroke(
                    1.dp,
                    if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
        ) {
            Row(
                Modifier.heightIn(min = 48.dp).padding(9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    value,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabIcon(
                    "edit",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun LabSettingNote(text: String, warning: Boolean = false) {
    Text(
        text,
        modifier =
            if (warning)
                Modifier.fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(12.dp)
            else Modifier,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 19.2.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun LabSettingDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
