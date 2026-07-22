package com.simplexray.an.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.simplexray.an.R
import com.simplexray.an.viewmodel.MainViewModel
import java.io.File

@Composable
fun ConfigScreen(
    onReloadConfig: () -> Unit,
    onEditConfigClick: (File) -> Unit,
    onDeleteConfigClick: (File, () -> Unit) -> Unit,
    mainViewModel: MainViewModel,
    listState: LazyListState
) {
    val showDeleteDialog = remember { mutableStateOf<File?>(null) }

    val isServiceEnabled by mainViewModel.isServiceEnabled.collectAsState()

    val files by mainViewModel.configFiles.collectAsState()
    val selectedFile by mainViewModel.selectedConfigFile.collectAsState()

    val subscriptions by mainViewModel.subscriptions.collectAsState()
    val subscriptionSync by mainViewModel.subscriptionSync.collectAsState()
    val subscriptionByFile by mainViewModel.subscriptionByFile.collectAsState()
    val showAddSubscriptionDialog = remember { mutableStateOf(false) }
    val showDeleteSubDialog = remember { mutableStateOf<com.simplexray.an.data.model.Subscription?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mainViewModel.refreshConfigFileList()
                mainViewModel.refreshSubscriptions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        mainViewModel.refreshConfigFileList()
        mainViewModel.refreshSubscriptions()
    }

    val subscriptionIds = subscriptions.map { it.id }.toSet()
    val manualFiles = files.filter { subscriptionByFile[it.name] !in subscriptionIds }
    val filesBySub: Map<String, List<File>> = subscriptions.associate { sub ->
        sub.id to files.filter { subscriptionByFile[it.name] == sub.id }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 10.dp, top = 10.dp),
            state = listState
        ) {
            item(key = "subs_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.subscriptions),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { showAddSubscriptionDialog.value = true }) {
                        Icon(painterResource(R.drawable.add), contentDescription = "Add")
                    }
                }
            }

            items(subscriptions, key = { "sub_" + it.id }) { sub ->
                SubscriptionCard(
                    sub = sub,
                    syncState = subscriptionSync[sub.id],
                    onSync = { mainViewModel.syncSubscription(sub.id) },
                    onDelete = { showDeleteSubDialog.value = sub }
                )
            }

            subscriptions.forEach { sub ->
                val subFiles = filesBySub[sub.id].orEmpty()
                if (subFiles.isNotEmpty()) {
                    item(key = "subgroup_" + sub.id) {
                        GroupHeader(sub.name)
                    }
                    items(subFiles, key = { it.absolutePath }) { file ->
                        ConfigRow(
                            file = file,
                            isSelected = file == selectedFile,
                            isServiceEnabled = isServiceEnabled,
                            showDelete = false,
                            onSelect = {
                                mainViewModel.updateSelectedConfigFile(file)
                                if (isServiceEnabled) onReloadConfig()
                            },
                            onEdit = { onEditConfigClick(file) },
                            onDelete = {}
                        )
                    }
                }
            }

            if (manualFiles.isNotEmpty()) {
                item(key = "manual_header") {
                    GroupHeader(stringResource(R.string.manual_configs))
                }
                items(manualFiles, key = { it.absolutePath }) { file ->
                    ConfigRow(
                        file = file,
                        isSelected = file == selectedFile,
                        isServiceEnabled = isServiceEnabled,
                        showDelete = true,
                        onSelect = {
                            mainViewModel.updateSelectedConfigFile(file)
                            if (isServiceEnabled) onReloadConfig()
                        },
                        onEdit = { onEditConfigClick(file) },
                        onDelete = { showDeleteDialog.value = file }
                    )
                }
            }

            if (files.isEmpty()) {
                item(key = "empty_state") {
                    Box(
                        modifier = Modifier
                            .fillParentMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.no_config_files),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    showDeleteDialog.value?.let { fileToDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog.value = null },
            title = { Text(stringResource(R.string.delete_config)) },
            text = { Text(fileToDelete.name) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog.value = null
                    onDeleteConfigClick(fileToDelete) {
                        mainViewModel.refreshConfigFileList()
                        mainViewModel.updateSelectedConfigFile(null)
                    }
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog.value = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAddSubscriptionDialog.value) {
        AddSubscriptionDialog(
            onDismiss = { showAddSubscriptionDialog.value = false },
            onConfirm = { name, url ->
                showAddSubscriptionDialog.value = false
                mainViewModel.addSubscription(name, url)
            }
        )
    }

    showDeleteSubDialog.value?.let { sub ->
        AlertDialog(
            onDismissRequest = { showDeleteSubDialog.value = null },
            title = { Text(stringResource(R.string.delete_subscription)) },
            text = { Text(sub.name) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteSubDialog.value = null
                    mainViewModel.deleteSubscription(sub.id)
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSubDialog.value = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ConfigRow(
    file: File,
    isSelected: Boolean,
    isServiceEnabled: Boolean,
    showDelete: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    file.name.removeSuffix(".json"),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onEdit) {
                    Icon(painterResource(R.drawable.edit), contentDescription = "Edit")
                }
                if (showDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(painterResource(R.drawable.delete), contentDescription = "Delete")
                    }
                }
            }
        }
    }
}
