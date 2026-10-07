package com.simplexray.an.feature.subscriptions.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.LabField
import com.simplexray.an.ui.components.LabIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit,
    initialUrl: String = "",
    editing: Boolean = false,
    onDelete: (() -> Unit)? = null,
    busy: Boolean = false,
    error: String? = null,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var clipboardError by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val parsed = url.trim().toHttpUrlOrNull()
    val presetLink = !editing && url.trim().startsWith("simplexray://routing/", ignoreCase = true)
    val validLink = parsed != null || presetLink
    val currentBusy by rememberUpdatedState(busy)
    val dismiss = { if (!busy) onDismiss() }
    LabDialog(
        title = if (editing) "Настройки подписки" else "Добавить подписку",
        onDismiss = dismiss,
        error = error,
        actions = {
            LabButton("Отмена", dismiss, Modifier.weight(1f), enabled = !busy, icon = "close")
            LabButton(
                if (busy) "Загрузка…" else if (editing) "Сохранить" else "Добавить",
                onClick = {
                    if (!busy && validLink) onConfirm(parsed?.host ?: "Пресет роутинга", url.trim())
                },
                modifier = Modifier.weight(1f),
                primary = true,
                enabled = !busy && validLink,
                icon = if (editing) "save" else "add",
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ссылка подписки", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LabField(
                    url,
                    { url = it },
                    Modifier.weight(1f).semantics { contentDescription = "Ссылка подписки" },
                    placeholder = "https://",
                    readOnly = busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                LabButton(
                    "Вставить",
                    onClick = {
                        val previous = url
                        scope.launch {
                            try {
                                val clip = clipboard.getClipEntry()?.clipData
                                val pasted =
                                    if (clip != null && clip.itemCount > 0)
                                        clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
                                    else null
                                if (url == previous && !currentBusy) {
                                    if (pasted.isNullOrBlank()) clipboardError = "Буфер обмена пуст"
                                    else {
                                        url = pasted
                                        clipboardError = null
                                    }
                                }
                            } catch (exception: Exception) {
                                if (exception is CancellationException) throw exception
                                clipboardError = "Не удалось прочитать буфер обмена"
                            }
                        }
                    },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Вставить ссылку подписки из буфера"
                        },
                    enabled = !busy,
                    icon = "clipboard",
                )
            }
        }
        if (url.isNotBlank() && !validLink)
            Text(
                if (editing) "Нужна ссылка http:// или https://"
                else "Вставь ссылку подписки или пресет роутинга",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        clipboardError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (onDelete != null)
            TextButton(onClick = onDelete, enabled = !busy) {
                LabIcon(
                    "delete",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text("Удалить подписку", color = MaterialTheme.colorScheme.error)
            }
    }
}
