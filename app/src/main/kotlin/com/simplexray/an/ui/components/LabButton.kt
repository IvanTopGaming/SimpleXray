package com.simplexray.an.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LabButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: String? = null,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val ink = if (danger) colors.error else if (primary) colors.background else colors.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        color =
            if (primary) colors.primary.copy(alpha = if (enabled) 1f else .55f)
            else colors.surfaceContainer,
        contentColor = ink.copy(alpha = if (enabled) 1f else .55f),
        border = BorderStroke(1.dp, if (primary) colors.primary else colors.outlineVariant),
        modifier = modifier.heightIn(min = if (compact) 36.dp else 48.dp),
    ) {
        Box(
            Modifier.padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) LabIcon(icon, modifier = Modifier.size(18.dp))
                Text(
                    text,
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal
                        ),
                )
            }
        }
    }
}
