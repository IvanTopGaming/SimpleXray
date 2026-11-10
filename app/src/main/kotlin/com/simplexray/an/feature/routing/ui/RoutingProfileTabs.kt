package com.simplexray.an.feature.routing.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class RoutingProfileTab(val id: String?, val title: String)

@Composable
internal fun RoutingProfileTabs(
    tabs: List<RoutingProfileTab>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { tab ->
            val active = tab.id == selected
            Surface(
                Modifier.heightIn(min = 40.dp)
                    .widthIn(max = 240.dp)
                    .testTag("routing-tab-${tab.id ?: "global"}")
                    .selectable(active, role = Role.Tab, onClick = { onSelect(tab.id) }),
                shape = RoundedCornerShape(20.dp),
                color = if (active) colors.primary else colors.surface,
                border = BorderStroke(1.dp, if (active) colors.primary else colors.outlineVariant),
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        tab.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) colors.background else colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
