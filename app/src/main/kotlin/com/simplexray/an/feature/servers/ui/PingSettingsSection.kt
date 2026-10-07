package com.simplexray.an.feature.servers.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.servers.model.ProbeMethod
import com.simplexray.an.feature.settings.state.InputFieldState
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.settings.LabEditableSetting
import com.simplexray.an.ui.components.settings.LabSettingDivider
import com.simplexray.an.ui.components.settings.LabSettingGroup
import com.simplexray.an.ui.components.settings.LabSettingValue
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PingSettingsSection(
    method: ProbeMethod,
    target: InputFieldState,
    timeout: InputFieldState,
    onMethodChange: (ProbeMethod) -> Unit,
    onTargetChange: (String) -> Unit,
    onTimeoutChange: (String) -> Unit,
    sheetState: SheetState,
    scope: CoroutineScope,
) {
    var choosingMethod by rememberSaveable { mutableStateOf(false) }
    LabSettingGroup("Пинг") {
        LabSettingValue(
            "Метод проверки",
            method.label(),
            if (method == ProbeMethod.TCP) "Проверка порта; работу прокси не гарантирует."
            else "HTTP через прокси: ответ 2xx, без перенаправлений.",
        ) {
            choosingMethod = true
        }
        LabSettingDivider()
        LabEditableSetting(
            "URL проверки",
            target,
            onTargetChange,
            sheetState,
            scope,
            if (method == ProbeMethod.TCP) "Для TCP используется адрес и порт сервера."
            else "Адрес HTTP-запроса для проверки работы прокси.",
            enabled = method != ProbeMethod.TCP,
            keyboardType = KeyboardType.Uri,
        )
        LabSettingDivider()
        LabEditableSetting(
            "Таймаут, мс",
            timeout,
            onTimeoutChange,
            sheetState,
            scope,
            "Ожидание ответа: от 1 до 30 000 миллисекунд.",
            keyboardType = KeyboardType.Number,
        )
        Spacer(Modifier.height(14.dp))
    }
    if (choosingMethod)
        LabDialog(
            "Метод проверки",
            { choosingMethod = false },
            actions = {
                LabButton(
                    "Закрыть",
                    { choosingMethod = false },
                    Modifier.fillMaxWidth(),
                    icon = "close",
                )
            },
        ) {
            ProbeMethod.entries.forEach { choice ->
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            choice == method,
                            role = Role.RadioButton,
                            onClick = {
                                onMethodChange(choice)
                                choosingMethod = false
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(choice == method, onClick = null)
                    Text(
                        choice.label(),
                        Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
}

private fun ProbeMethod.label(): String =
    when (this) {
        ProbeMethod.TCP -> "TCP · доступность порта"
        ProbeMethod.HTTP_GET -> "HTTP GET · через прокси"
        ProbeMethod.HTTP_HEAD -> "HTTP HEAD · через прокси"
    }
