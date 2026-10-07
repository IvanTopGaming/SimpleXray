package com.simplexray.an.feature.servers.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.an.R
import com.simplexray.an.feature.servers.state.ConfigEditUiEvent
import com.simplexray.an.feature.servers.state.ConfigEditViewModel
import com.simplexray.an.feature.servers.ui.editor.bracketMatcherTransformation
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabField
import com.simplexray.an.ui.components.LabIcon
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ConfigEditScreen(
    onBackClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    viewModel: ConfigEditViewModel,
) {
    var showMenu by remember { mutableStateOf(false) }
    val filename by viewModel.filename.collectAsStateWithLifecycle()
    val configTextFieldValue by viewModel.configTextFieldValue.collectAsStateWithLifecycle()
    val filenameErrorMessage by viewModel.filenameErrorMessage.collectAsStateWithLifecycle()
    val hasConfigChanged by viewModel.hasConfigChanged.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val shareLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    LaunchedEffect(viewModel) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                is ConfigEditUiEvent.NavigateBack -> onBackClick()
                is ConfigEditUiEvent.ShowSnackbar ->
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Short)
                is ConfigEditUiEvent.ShareContent -> {
                    val shareIntent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, event.content)
                        }
                    shareLauncher.launch(Intent.createChooser(shareIntent, null))
                }
            }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .pointerInput(onBackClick) { detectTapGestures { onBackClick() } }
            .systemBarsPadding()
            .imePadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.pointerInput(Unit) { detectTapGestures {} },
            shape = RoundedCornerShape(16.dp),
            color = colors.background,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (viewModel.readOnly) "Конфигурация сервера" else "Редактировать сервер",
                        modifier = Modifier.weight(1f),
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontSize = 19.sp,
                                lineHeight = 23.75.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more),
                            )
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share)) },
                                leadingIcon = { LabIcon("send", modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    viewModel.shareConfigFile()
                                    showMenu = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (viewModel.readOnly) {
                        Text(
                            "Только просмотр · сервер из подписки",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Название", style = MaterialTheme.typography.bodyMedium)
                        LabField(
                            filename,
                            viewModel::onFilenameChange,
                            Modifier.fillMaxWidth().semantics { contentDescription = "Имя файла" },
                            readOnly = viewModel.readOnly,
                        )
                        filenameErrorMessage?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Конфигурация · JSON", style = MaterialTheme.typography.bodyMedium)
                        BasicTextField(
                            value = configTextFieldValue,
                            readOnly = viewModel.readOnly,
                            onValueChange = { newValue ->
                                val newText = newValue.text
                                val oldText = configTextFieldValue.text
                                val cursorPosition = newValue.selection.start
                                if (
                                    newText.length == oldText.length + 1 &&
                                        cursorPosition > 0 &&
                                        newText[cursorPosition - 1] == '\n'
                                ) {
                                    val (text, cursor) =
                                        viewModel.handleAutoIndent(newText, cursorPosition - 1)
                                    viewModel.onConfigContentChange(
                                        TextFieldValue(text, selection = TextRange(cursor))
                                    )
                                } else {
                                    viewModel.onConfigContentChange(newValue)
                                }
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 180.dp, max = 220.dp)
                                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(9.dp))
                                    .background(colors.surfaceContainer, RoundedCornerShape(9.dp))
                                    .padding(9.dp)
                                    .semantics { contentDescription = "Содержимое" },
                            textStyle =
                                MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 24.sp,
                                    color =
                                        if (viewModel.readOnly) colors.onSurfaceVariant
                                        else colors.onSurface,
                                ),
                            cursorBrush = SolidColor(colors.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            visualTransformation =
                                bracketMatcherTransformation(configTextFieldValue),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LabButton(
                        if (viewModel.readOnly) "Закрыть" else "Отмена",
                        onBackClick,
                        Modifier.weight(1f),
                        icon = "close",
                    )
                    if (!viewModel.readOnly) {
                        LabButton(
                            "Сохранить",
                            onClick = {
                                viewModel.saveConfigFile()
                                focusManager.clearFocus()
                            },
                            modifier =
                                Modifier.weight(1f).semantics { contentDescription = "Сохранить" },
                            primary = true,
                            enabled = hasConfigChanged,
                            icon = "save",
                        )
                    }
                }
                if (snackbarHostState.currentSnackbarData != null) {
                    Spacer(Modifier.height(8.dp))
                    SnackbarHost(snackbarHostState)
                }
            }
        }
    }
}
