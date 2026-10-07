package com.simplexray.an.feature.servers.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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

@Composable
fun ImportServerDialog(
    initialContent: String,
    onDismiss: () -> Unit,
    onImport: suspend (String, String?) -> Boolean,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable {
        mutableStateOf(initialContent.takeUnless { it.trimStart().startsWith("{") }.orEmpty())
    }
    var json by rememberSaveable {
        mutableStateOf(initialContent.takeIf { it.trimStart().startsWith("{") }.orEmpty())
    }
    var advanced by rememberSaveable { mutableStateOf(initialContent.trimStart().startsWith("{")) }
    var importingJson by rememberSaveable {
        mutableStateOf(initialContent.trimStart().startsWith("{"))
    }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf(false) }
    var clipboardError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val content = if (importingJson) json else link
    LabDialog(
        title = "Добавить сервер",
        onDismiss = { if (!busy) onDismiss() },
        error = if (error) "Не удалось импортировать. Проверь ссылку или JSON." else clipboardError,
        actions = {
            LabButton("Отмена", onDismiss, Modifier.weight(1f), enabled = !busy, icon = "close")
            LabButton(
                if (busy) "Сохранение…" else "Сохранить",
                enabled = content.isNotBlank() && !busy,
                modifier = Modifier.weight(1f),
                primary = true,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            if (onImport(content.trim(), name.trim().takeIf(String::isNotEmpty)))
                                onDismiss()
                            else error = true
                        } finally {
                            busy = false
                        }
                    }
                },
                icon = "save",
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ссылка подключения", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LabField(
                    link,
                    {
                        if (!busy) {
                            link = it
                            importingJson = false
                            error = false
                        }
                    },
                    modifier =
                        Modifier.weight(1f).semantics { contentDescription = "Ссылка на сервер" },
                    placeholder = "vless://, vmess://, trojan://",
                    readOnly = busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                LabButton(
                    "Вставить",
                    enabled = !busy,
                    onClick = {
                        val previousLink = link
                        val previousJson = json
                        scope.launch {
                            try {
                                val clip = clipboard.getClipEntry()?.clipData
                                val pasted =
                                    if (clip != null && clip.itemCount > 0)
                                        clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
                                    else null
                                if (!busy && link == previousLink && json == previousJson) {
                                    if (pasted.isNullOrBlank()) clipboardError = "Буфер обмена пуст"
                                    else {
                                        importingJson = pasted.startsWith("{")
                                        if (importingJson) {
                                            json = pasted
                                            advanced = true
                                        } else link = pasted
                                        clipboardError = null
                                        error = false
                                    }
                                }
                            } catch (exception: Exception) {
                                if (exception is CancellationException) throw exception
                                clipboardError = "Не удалось прочитать буфер обмена"
                            }
                        }
                    },
                    modifier =
                        Modifier.semantics { contentDescription = "Вставить ссылку из буфера" },
                    icon = "clipboard",
                )
            }
            TextButton(
                onClick = {},
                enabled = false,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                LabIcon("qr", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Сканировать QR-код", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Название",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabField(
                name,
                { if (!busy) name = it },
                Modifier.fillMaxWidth().semantics { contentDescription = "Название сервера" },
                placeholder = "Из ссылки или адреса сервера",
                readOnly = busy,
            )
        }
        TextButton(
            onClick = { advanced = !advanced },
            enabled = !busy,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.heightIn(min = 48.dp),
            colors =
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            LabIcon("arrow", modifier = Modifier.size(18.dp).rotate(if (advanced) 90f else 0f))
            Spacer(Modifier.width(8.dp))
            Text("Расширенный ввод", style = MaterialTheme.typography.bodyMedium)
        }
        if (advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Конфигурация · JSON", style = MaterialTheme.typography.bodyMedium)
                LabField(
                    json,
                    {
                        if (!busy) {
                            json = it
                            importingJson = true
                            error = false
                        }
                    },
                    Modifier.fillMaxWidth().heightIn(min = 180.dp).semantics {
                        contentDescription = "Конфигурация JSON"
                    },
                    singleLine = false,
                    readOnly = busy,
                )
            }
        }
    }
}
