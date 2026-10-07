package com.simplexray.an.feature.logs.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.an.R
import com.simplexray.an.feature.logs.state.LogViewModel
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabField
import com.simplexray.an.ui.components.labGap
import com.simplexray.an.ui.components.labHorizontalPadding
import com.simplexray.an.ui.theme.ScrollbarDefaults
import my.nanihadesuka.compose.LazyColumnScrollbar

@Composable
fun LogScreen(logViewModel: LogViewModel, listState: LazyListState) {
    val context = LocalContext.current
    val filteredEntries by logViewModel.filteredEntries.collectAsStateWithLifecycle()
    val search by logViewModel.searchQuery.collectAsStateWithLifecycle()
    var initialLoad by remember { mutableStateOf(true) }
    var copyStatus by remember { mutableStateOf("") }

    DisposableEffect(logViewModel, context) {
        logViewModel.registerLogReceiver(context)
        logViewModel.loadLogs()
        onDispose { logViewModel.unregisterLogReceiver(context) }
    }

    LaunchedEffect(filteredEntries) {
        if (filteredEntries.isNotEmpty() && initialLoad) {
            listState.scrollToItem(0)
            initialLoad = false
        }
    }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = labHorizontalPadding(), vertical = labGap()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "События ядра и соединения. Журналы подключений и DNS могут содержать адреса и домены.",
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.2.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabField(
                value = search,
                onValueChange = logViewModel::onSearchQueryChange,
                placeholder = "Поиск по журналу",
                modifier = Modifier.weight(1f),
            )
            if (search.isNotEmpty()) {
                IconButton(
                    onClick = { logViewModel.onSearchQueryChange("") },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Default.Clear, "Очистить поиск по журналу", Modifier.size(18.dp))
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().height(300.dp).testTag("diagnostic-log"),
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            SelectionContainer {
                if (filteredEntries.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().padding(9.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Text(
                            stringResource(R.string.no_log_entries),
                            style =
                                MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumnScrollbar(
                        state = listState,
                        settings = ScrollbarDefaults.defaultScrollbarSettings(),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(9.dp),
                            reverseLayout = true,
                        ) {
                            items(filteredEntries) { entry -> LogEntryItem(entry) }
                        }
                    }
                }
            }
        }
        LabButton(
            "Скопировать журнал",
            onClick = {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("SimpleXray", filteredEntries.joinToString("\n"))
                )
                copyStatus = "Журнал скопирован"
            },
            enabled = filteredEntries.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            icon = "clipboard",
        )
        if (copyStatus.isNotEmpty()) {
            Text(
                copyStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun LogEntryItem(logEntry: String) {
    Text(
        text = logEntry,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
