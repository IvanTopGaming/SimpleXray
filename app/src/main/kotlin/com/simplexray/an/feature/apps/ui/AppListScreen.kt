package com.simplexray.an.feature.apps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.R
import com.simplexray.an.feature.apps.model.AppRoutingMode
import com.simplexray.an.feature.apps.state.AppListViewModel
import com.simplexray.an.feature.apps.state.AppListViewUiEvent
import com.simplexray.an.ui.components.LabField
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.labGap
import com.simplexray.an.ui.components.labHorizontalPadding
import com.simplexray.an.ui.components.settings.LabSettingDescription
import kotlinx.coroutines.flow.collectLatest

@Composable
fun AppListScreen(viewModel: AppListViewModel, onBackClick: () -> Unit, embedded: Boolean = false) {
    val isLoading = viewModel.isLoading
    val searchQuery = viewModel.searchQuery
    val filteredList = viewModel.filteredList
    val showSystemApps = viewModel.showSystemApps
    val focusManager = LocalFocusManager.current
    val lazyListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val colors = MaterialTheme.colorScheme
    val vpnDisabled = viewModel.prefs.disableVpn
    val selectionError = viewModel.selectionError
    val onBack = { if (viewModel.canLeaveScreen()) onBackClick() }

    BackHandler(enabled = viewModel.appRoutingMode == AppRoutingMode.INCLUDE && !vpnDisabled) {
        onBack()
    }

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (lazyListState.isScrollInProgress) focusManager.clearFocus()
    }
    LaunchedEffect(searchQuery, showSystemApps) { lazyListState.scrollToItem(0) }
    LaunchedEffect(viewModel) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                is AppListViewUiEvent.ShowSnackbar ->
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Short)
            }
        }
    }

    Scaffold(
        contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("app-list"),
            state = lazyListState,
            contentPadding = PaddingValues(horizontal = labHorizontalPadding(), vertical = labGap()),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (!embedded) {
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("app-page-header"),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                                LabIcon("arrow", "Назад", Modifier.rotate(180f))
                            }
                            Text(
                                "Приложения",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp),
                            )
                            AppActions(viewModel)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, colors.outlineVariant),
                        color = colors.background,
                        modifier = Modifier.fillMaxWidth().testTag("app-mode-group"),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "VPN ДЛЯ ПРИЛОЖЕНИЙ",
                                    Modifier.weight(1f),
                                    style =
                                        MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 10.sp,
                                            letterSpacing = .7.sp,
                                        ),
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            Column(
                                Modifier.padding(vertical = 13.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("Режим", style = MaterialTheme.typography.bodyMedium)
                                LabSettingDescription(
                                    if (vpnDisabled)
                                        "Доступен только при включённом VPN-интерфейсе."
                                    else
                                        when (viewModel.appRoutingMode) {
                                            AppRoutingMode.ALL ->
                                                "VPN для всех. Выбор приложений сохранён."
                                            AppRoutingMode.EXCLUDE ->
                                                "Выбранные приложения идут напрямую."
                                            AppRoutingMode.INCLUDE ->
                                                "VPN только для выбранных приложений."
                                        }
                                )
                                AppPolicy(viewModel, !vpnDisabled, embedded)
                            }
                            if (selectionError != null)
                                Text(
                                    selectionError,
                                    Modifier.padding(top = 8.dp, bottom = 14.dp),
                                    style =
                                        MaterialTheme.typography.bodySmall.copy(
                                            lineHeight = 19.2.sp
                                        ),
                                    color = colors.error,
                                )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LabField(
                            searchQuery,
                            viewModel::onSearchQueryChange,
                            Modifier.weight(1f),
                            placeholder = "Найти приложение",
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                Icon(
                                    Icons.Default.Clear,
                                    stringResource(R.string.clear_search),
                                    Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    if (isLoading) {
                        Box(
                            Modifier.fillMaxWidth().height(64.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            com.simplexray.an.ui.components.LoadingSpinner()
                        }
                    } else if (filteredList.isEmpty()) {
                        Text(
                            stringResource(R.string.apps_not_found),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            itemsIndexed(filteredList, key = { _, pkg -> pkg.packageName }) { index, pkg ->
                AppItem(
                    pkg,
                    { viewModel.onPackageSelected(pkg, it) },
                    enabled = viewModel.selectionEnabled,
                    first = index == 0,
                    last = index == filteredList.lastIndex,
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                ) {
                    Text(
                        "Исключённые приложения не попадают в Xray: доменные правила к ним не применяются. Системная блокировка соединений без VPN может лишить их доступа к сети.",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.2.sp),
                    )
                }
            }
        }
    }
}
