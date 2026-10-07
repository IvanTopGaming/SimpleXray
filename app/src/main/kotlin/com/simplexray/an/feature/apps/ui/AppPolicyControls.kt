package com.simplexray.an.feature.apps.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.simplexray.an.R
import com.simplexray.an.feature.apps.model.AppRoutingMode
import com.simplexray.an.feature.apps.state.AppListViewModel
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.settings.LabSettingDescription

@Composable
internal fun AppPolicy(viewModel: AppListViewModel, enabled: Boolean, showActions: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "Режим VPN для приложений"
                },
        ) {
            Row(
                Modifier.padding(9.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (viewModel.appRoutingMode) {
                        AppRoutingMode.ALL -> "Все приложения"
                        AppRoutingMode.EXCLUDE -> "Кроме выбранных"
                        AppRoutingMode.INCLUDE -> "Только выбранные"
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .45f),
                )
                LabIcon("arrow", modifier = Modifier.size(12.dp).rotate(90f))
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Все приложения") },
                leadingIcon = { LabIcon("apps", modifier = Modifier.size(18.dp)) },
                onClick = {
                    viewModel.onAppRoutingModeChange(AppRoutingMode.ALL)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Кроме выбранных") },
                leadingIcon = { LabIcon("route", modifier = Modifier.size(18.dp)) },
                onClick = {
                    viewModel.onAppRoutingModeChange(AppRoutingMode.EXCLUDE)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Только выбранные") },
                leadingIcon = { LabIcon("select-all", modifier = Modifier.size(18.dp)) },
                onClick = {
                    viewModel.onAppRoutingModeChange(AppRoutingMode.INCLUDE)
                    expanded = false
                },
            )
            if (showActions) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AppActionItems(viewModel) { expanded = false }
            }
        }
    }
}

@Composable
internal fun AppActions(viewModel: AppListViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.more), Modifier.size(20.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            AppActionItems(viewModel) { expanded = false }
        }
    }
}

@Composable
private fun AppActionItems(viewModel: AppListViewModel, close: () -> Unit) {
    val context = LocalContext.current
    DropdownMenuItem(
        text = { Text(stringResource(R.string.select_all)) },
        leadingIcon = { LabIcon("select-all", modifier = Modifier.size(18.dp)) },
        enabled = viewModel.selectionEnabled,
        onClick = {
            viewModel.selectAll()
            close()
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.inverse_selection)) },
        leadingIcon = { LabIcon("shuffle", modifier = Modifier.size(18.dp)) },
        enabled = viewModel.selectionEnabled,
        onClick = {
            viewModel.inverseSelection()
            close()
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.export_to_clipboard)) },
        leadingIcon = { LabIcon("export", modifier = Modifier.size(18.dp)) },
        onClick = {
            viewModel.exportAppsToClipboard(context)
            close()
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.import_from_clipboard)) },
        leadingIcon = { LabIcon("import", modifier = Modifier.size(18.dp)) },
        onClick = {
            viewModel.importAppsFromClipboard(context)
            close()
        },
    )
    DropdownMenuItem(
        leadingIcon = { LabIcon("settings", modifier = Modifier.size(18.dp)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.show_system_apps))
                    LabSettingDescription("Показывает приложения, встроенные в Android.")
                }
                Checkbox(viewModel.showSystemApps, onCheckedChange = null)
            }
        },
        onClick = {
            viewModel.onShowSystemAppsChange(!viewModel.showSystemApps)
            close()
        },
    )
}
