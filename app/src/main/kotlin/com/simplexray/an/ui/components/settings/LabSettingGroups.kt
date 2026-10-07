package com.simplexray.an.ui.components.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun LabSettingGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            Text(
                title,
                Modifier.padding(top = 14.dp, bottom = 4.dp),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
internal fun LabSettingDetails(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight(550)),
                )
                Text(if (expanded) "−" else "＋", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) content()
        }
    }
}
