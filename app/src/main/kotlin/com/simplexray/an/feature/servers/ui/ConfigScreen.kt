package com.simplexray.an.feature.servers.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.feature.servers.model.ServerDetails
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.labGap
import com.simplexray.an.ui.components.labHorizontalPadding
import java.io.File

@Composable
fun ConfigScreen(
    onReloadConfig: () -> Unit,
    onEditConfigClick: (File) -> Unit,
    onDeleteConfigClick: (File, () -> Unit) -> Unit,
    mainViewModel: MainViewModel,
    listState: LazyListState,
    subscriptionFilter: String? = null,
    filterRequest: Int = 0,
    onClearFilter: () -> Unit = {},
) {
    val files by mainViewModel.configFiles.collectAsState()
    val selectedFile by mainViewModel.selectedConfigFile.collectAsState()
    val connected by mainViewModel.isServiceEnabled.collectAsState()
    val subscriptions by mainViewModel.subscriptions.collectAsState()
    val owners by mainViewModel.subscriptionByFile.collectAsState()
    val checks by mainViewModel.serverChecks.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("name") }
    var grouping by rememberSaveable { mutableStateOf(ServerGrouping.SUBSCRIPTION) }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    var appliedFilterRequest by rememberSaveable { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<File?>(null) }
    val details by mainViewModel.serverDetails.collectAsState()
    LaunchedEffect(query, subscriptionFilter) { listState.scrollToItem(0) }
    LaunchedEffect(filterRequest) {
        if (appliedFilterRequest != filterRequest) {
            query = ""
            collapsed = emptyList()
            appliedFilterRequest = filterRequest
        }
    }
    val names = subscriptions.associate { it.id to it.displayName }
    val groups =
        configGroups(
            files,
            owners,
            names,
            details,
            grouping,
            query,
            sort,
            checks.results,
            subscriptionFilter,
        )
    val visible = groups.flatMap { it.files }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = labHorizontalPadding(),
                top = labGap(),
                end = labHorizontalPadding(),
                bottom = 28.dp,
            ),
        state = listState,
    ) {
        item {
            ConfigListControls(
                query,
                { query = it },
                sort,
                { sort = it },
                grouping,
                { grouping = it },
                checks,
                visible.isNotEmpty(),
                onCheckServers = {
                    if (checks.running) mainViewModel.cancelServerChecks()
                    else mainViewModel.checkServers(visible)
                },
            )
        }
        if (subscriptionFilter != null) {
            item {
                InputChip(
                    selected = true,
                    onClick = onClearFilter,
                    label = {
                        Text(
                            names[subscriptionFilter] ?: "Подписка",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            "Сбросить фильтр подписки",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        groups.forEach { group ->
            val groupKey = group.key
            val servers = group.files
            if (grouping != ServerGrouping.NONE) {
                item(key = "group_" + groupKey) {
                    ServerGroupHeader(
                        group.title,
                        servers.size,
                        group.isCollapsed(collapsed, query),
                        onToggle = {
                            collapsed =
                                if (groupKey in collapsed) collapsed - groupKey
                                else collapsed + groupKey
                        },
                    )
                }
            }
            if (!group.isCollapsed(collapsed, query)) {
                items(servers, key = { it.absolutePath }) { file ->
                    ConfigRow(
                        file,
                        file == selectedFile,
                        owners[file.name]?.let(names::containsKey) == true,
                        details[file] ?: ServerDetails(),
                        names[owners[file.name]] ?: "Ручной сервер",
                        result = checks.results[file.absolutePath],
                        checking = file.absolutePath in checks.checking,
                        onSelect = {
                            if (file != selectedFile) {
                                mainViewModel.updateSelectedConfigFile(file)
                                if (connected) onReloadConfig()
                            }
                        },
                        onEdit = { onEditConfigClick(file) },
                        onDelete = { deleting = file },
                    )
                    Spacer(Modifier.height(9.dp))
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                Text(
                    if (files.isEmpty()) "Добавь сервер или подписку, чтобы начать."
                    else "Ничего не найдено",
                    modifier = Modifier.padding(vertical = 32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить сервер?") },
            text = { Text(file.nameWithoutExtension) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        onDeleteConfigClick(file) { mainViewModel.refreshConfigFileList() }
                    }
                ) {
                    LabIcon(
                        "delete",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    LabIcon("close", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Отмена")
                }
            },
        )
    }
}
