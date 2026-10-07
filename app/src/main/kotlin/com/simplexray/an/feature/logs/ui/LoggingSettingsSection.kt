package com.simplexray.an.feature.logs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.logs.model.LogSettings
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.settings.LabSettingDetails
import com.simplexray.an.ui.components.settings.LabSettingDivider
import com.simplexray.an.ui.components.settings.LabSettingNote
import com.simplexray.an.ui.components.settings.LabSettingToggle
import com.simplexray.an.ui.components.settings.LabSettingValue

@Composable
internal fun LoggingSettingsSection(prefs: Preferences) {
    var stored by remember(prefs) { mutableStateOf(prefs.logSettingsJson) }
    val settings = remember(stored) { runCatching { LogSettings.decode(stored) }.getOrNull() }
    var selectingLevel by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    fun persist(json: String?): Boolean {
        prefs.logSettingsJson = json
        if (prefs.logSettingsJson != json) {
            saveError = "Не удалось сохранить настройки журналов. Попробуй ещё раз."
            return false
        }
        stored = json
        saveError = null
        return true
    }

    fun save(updated: LogSettings): Boolean = persist(updated.encode())

    LabSettingDetails("Логи и статистика", initiallyExpanded = true) {
        saveError?.let { LabSettingNote(it, warning = true) }
        if (settings == null) {
            LabSettingNote(
                "Настройки журналов повреждены. Сбрось их к значениям по умолчанию.",
                warning = true,
            )
            LabButton(
                "Сбросить настройки журналов",
                { persist(null) },
                Modifier.fillMaxWidth(),
                danger = true,
                icon = "reset",
            )
        } else {
            LabSettingValue(
                "Уровень журнала",
                settings.level,
                "Подробность логов ядра со следующего подключения.",
                modifier = Modifier.semantics { contentDescription = "Уровень журнала" },
            ) {
                selectingLevel = true
            }
            LabSettingDivider()
            LabSettingToggle(
                "Журнал подключений",
                "Записывать подключения, включая адреса назначений.",
                settings.access,
                { save(settings.copy(access = it)) },
            )
            LabSettingDivider()
            LabSettingToggle(
                "Журнал DNS",
                "Записывать DNS-запросы, включая доменные имена.",
                settings.dns,
                { save(settings.copy(dns = it)) },
            )
            LabSettingDivider()
            LabSettingToggle(
                "Скрывать IP в логах",
                "Маскировать IP-адреса; домены остаются видны.",
                settings.maskIp,
                { save(settings.copy(maskIp = it)) },
            )
            LabSettingDivider()
            LabSettingToggle(
                "Статистика трафика",
                "Скорость и объём на главной и в уведомлении после подключения.",
                settings.trafficStats,
                { save(settings.copy(trafficStats = it)) },
            )
        }
    }
    if (selectingLevel && settings != null) {
        LabDialog(
            "Уровень журнала",
            { selectingLevel = false },
            actions = {
                LabButton(
                    "Закрыть",
                    { selectingLevel = false },
                    Modifier.fillMaxWidth(),
                    icon = "close",
                )
            },
            error = saveError,
        ) {
            LogSettings.levels.forEach { level ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        if (save(settings.copy(level = level))) selectingLevel = false
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(settings.level == level, onClick = null)
                    Text(level, Modifier.weight(1f).padding(start = 8.dp))
                }
            }
        }
    }
}
