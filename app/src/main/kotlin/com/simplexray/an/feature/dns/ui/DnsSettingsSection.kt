package com.simplexray.an.feature.dns.ui

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
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.dns.model.DnsQueryStrategy
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.settings.LabSettingDivider
import com.simplexray.an.ui.components.settings.LabSettingGroup
import com.simplexray.an.ui.components.settings.LabSettingNote
import com.simplexray.an.ui.components.settings.LabSettingToggle
import com.simplexray.an.ui.components.settings.LabSettingValue

@Composable
internal fun DnsSettingsSection(prefs: Preferences) {
    var stored by remember(prefs) { mutableStateOf(prefs.dnsSettingsJson) }
    val settings =
        remember(stored) { runCatching { DnsSettings.decode(stored, prefs.dnsIpv4) }.getOrNull() }
    var editing by remember { mutableStateOf<DnsField?>(null) }
    var choice by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    fun save(updated: DnsSettings): Boolean {
        val json =
            try {
                updated.encode()
            } catch (invalid: IllegalArgumentException) {
                saveError = invalid.message
                return false
            }
        prefs.dnsSettingsJson = json
        if (prefs.dnsSettingsJson != json) {
            saveError = "Не удалось сохранить DNS. Попробуй ещё раз."
            return false
        }
        stored = json
        saveError = null
        return true
    }

    if (saveError != null && editing == null) LabSettingNote(saveError!!, warning = true)
    if (settings == null) {
        LabSettingGroup("DNS") {
            LabSettingNote(
                "Настройки DNS повреждены. Сбрось их к значениям по умолчанию.",
                warning = true,
            )
            LabButton(
                "Сбросить DNS",
                {
                    val defaults =
                        runCatching { DnsSettings.decode(null, prefs.dnsIpv4) }
                            .getOrDefault(DnsSettings())
                    save(defaults)
                },
                Modifier.fillMaxWidth(),
                danger = true,
                icon = "reset",
            )
        }
        return
    }
    LabSettingGroup("Резолверы Xray") {
        for (field in listOf(DnsField.PRIMARY, DnsField.PRIMARY_BOOTSTRAP)) {
            DnsValue(field, settings) {
                saveError = null
                editing = field
            }
            LabSettingDivider()
        }
        LabSettingToggle(
            "Резервный DNS",
            "Использует запасной сервер, если основной не ответил.",
            settings.fallbackEnabled,
            { save(settings.copy(fallbackEnabled = it)) },
        )
        LabSettingDivider()
        for (field in listOf(DnsField.FALLBACK, DnsField.FALLBACK_BOOTSTRAP)) {
            DnsValue(field, settings) {
                saveError = null
                editing = field
            }
            LabSettingDivider()
        }
        LabSettingValue(
            "Маршрут DNS",
            if (settings.route == RouteTarget.PROXY) "Через прокси" else "Напрямую",
            "Маршрут основного и резервного DNS. Прямой DNS — в обход.",
        ) {
            choice = "route"
        }
    }
    LabSettingGroup("SplitDNS") {
        for (field in listOf(DnsField.DIRECT, DnsField.DIRECT_BOOTSTRAP)) {
            DnsValue(field, settings) {
                saveError = null
                editing = field
            }
            if (field == DnsField.DIRECT) LabSettingDivider()
        }
    }
    LabSettingGroup("Разрешение имён") {
        LabSettingValue(
            "Тип запросов",
            settings.queryStrategy.label(),
            "Выбирает IPv4 или IPv6; авто учитывает настройки подключения.",
        ) {
            choice = "strategy"
        }
        LabSettingDivider()
        LabSettingToggle(
            "Кэшировать ответы",
            "Сохраняет ответы DNS, чтобы реже запрашивать их заново.",
            settings.cacheEnabled,
            { save(settings.copy(cacheEnabled = it)) },
        )
        LabSettingDivider()
        LabSettingToggle(
            "FakeIP",
            "Сохраняет домен для правил маршрутизации. Включён по умолчанию.",
            settings.fakeIpEnabled,
            { save(settings.copy(fakeIpEnabled = it)) },
        )
    }
    editing?.let { field ->
        DnsFieldEditor(
            field,
            settings,
            saveError,
            {
                editing = null
                saveError = null
            },
        ) { updated ->
            if (save(updated)) editing = null
        }
    }
    choice?.let { selection ->
        val routes = listOf(RouteTarget.PROXY, RouteTarget.DIRECT)
        val labels =
            if (selection == "route") listOf("Через прокси", "Напрямую")
            else DnsQueryStrategy.entries.map { it.label() }
        val selected =
            if (selection == "route") routes.indexOf(settings.route)
            else settings.queryStrategy.ordinal
        LabDialog(
            if (selection == "route") "Маршрут DNS" else "Тип запросов",
            { choice = null },
            error = saveError,
            actions = {
                LabButton("Закрыть", { choice = null }, Modifier.fillMaxWidth(), icon = "close")
            },
        ) {
            labels.forEachIndexed { index, label ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        val updated =
                            if (selection == "route") settings.copy(route = routes[index])
                            else settings.copy(queryStrategy = DnsQueryStrategy.entries[index])
                        if (save(updated)) choice = null
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected == index, onClick = null)
                    Text(label, Modifier.weight(1f).padding(start = 8.dp))
                }
            }
        }
    }
}
