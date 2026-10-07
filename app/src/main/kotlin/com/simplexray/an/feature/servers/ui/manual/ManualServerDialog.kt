package com.simplexray.an.feature.servers.ui.manual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.servers.manual.compiler.ManualServerCompiler
import com.simplexray.an.feature.servers.manual.model.ManualServerDraft
import com.simplexray.an.feature.servers.manual.model.ManualValue
import com.simplexray.an.feature.servers.manual.schema.ManualSchemaRegistry
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.LabField
import com.simplexray.an.ui.components.settings.LabSettingDescription
import com.simplexray.an.ui.components.settings.LabSettingDivider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ManualServerDialog(onDismiss: () -> Unit, onImport: suspend (String, String?) -> Boolean) {
    val context = LocalContext.current
    val schemas = remember(context) { runCatching { ManualSchemaRegistry.fromAssets(context) } }
    val registry = schemas.getOrNull()
    if (registry == null) {
        LabDialog(
            "Добавить сервер",
            onDismiss,
            actions = { LabButton("Закрыть", onDismiss, Modifier.fillMaxWidth(), icon = "close") },
            error = "Не удалось открыть редактор параметров",
        ) {}
        return
    }
    var encoded by rememberSaveable { mutableStateOf(registry.initialDraft().encode()) }
    val draft =
        remember(encoded) {
            runCatching { ManualServerDraft.decode(encoded) }.getOrElse { registry.initialDraft() }
        }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val protocol = registry.protocol(draft.protocol)
    fun update(value: ManualServerDraft) {
        if (!busy) {
            encoded = value.encode()
            error = null
        }
    }
    LabDialog(
        title = "Настроить сервер вручную",
        onDismiss = { if (!busy) onDismiss() },
        error = error,
        actions = {
            LabButton("Отмена", onDismiss, Modifier.weight(1f), enabled = !busy, icon = "close")
            LabButton(
                if (busy) "Сохранение…" else "Сохранить",
                {
                    focus.clearFocus()
                    busy = true
                    scope.launch {
                        try {
                            val result =
                                withContext(Dispatchers.IO) {
                                    ManualServerCompiler(registry).compile(draft)
                                }
                            if (onImport(result.json, result.name)) onDismiss()
                            else error = "Не удалось сохранить сервер. Проверь параметры."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (invalid: Exception) {
                            error = invalid.message ?: "Проверь параметры сервера"
                        } finally {
                            busy = false
                        }
                    }
                },
                Modifier.weight(1f),
                primary = true,
                enabled = !busy,
                icon = "save",
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Название профиля", style = MaterialTheme.typography.bodyMedium)
            LabSettingDescription("Имя для списка серверов и уведомления.")
            LabField(
                draft.name,
                { update(draft.copy(name = it)) },
                Modifier.fillMaxWidth().testTag("manual.name"),
                placeholder = "Название или адрес сервера",
                readOnly = busy,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Протокол", style = MaterialTheme.typography.bodyMedium)
            LabSettingDescription("Протокол, который использует твой сервер.")
            ManualChoice(
                draft.protocol,
                registry.protocols.map { it.id },
                "manual.protocol",
                "Протокол",
                { registry.protocol(it).label },
                enabled = !busy,
            ) {
                if (!busy && it != draft.protocol) update(registry.initialDraft(it, draft))
            }
        }
        LabSettingDivider()
        Text("Подключение", style = MaterialTheme.typography.bodyMedium)
        ManualObjectFieldsEditor(
            registry.fields(protocol.schema),
            draft.settings,
            registry,
            pathPrefix = "manual.settings.${protocol.id}",
            enabled = !busy,
        ) {
            update(draft.copy(settings = it))
        }
        if (protocol.transport) {
            LabSettingDivider()
            Text("Транспорт и защита", style = MaterialTheme.typography.bodyMedium)
            ManualObjectFieldsEditor(
                registry.fields("StreamConfig"),
                draft.stream ?: ManualValue.objectValue(),
                registry,
                pathPrefix = "manual.stream",
                enabled = !busy,
            ) {
                update(draft.copy(stream = it))
            }
        }
    }
}
