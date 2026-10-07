package com.simplexray.an.feature.servers.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplexray.an.ui.components.LabIcon

@Composable
internal fun ServerFilter(
    label: String,
    value: String,
    modifier: Modifier,
    options: @Composable ColumnScope.(() -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Surface(
                onClick = { expanded = true },
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = label
                    },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        value,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LabIcon("arrow", modifier = Modifier.size(12.dp).rotate(90f))
                }
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                options { expanded = false }
            }
        }
    }
}
