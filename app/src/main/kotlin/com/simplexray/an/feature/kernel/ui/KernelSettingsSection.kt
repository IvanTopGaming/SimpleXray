package com.simplexray.an.feature.kernel.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.simplexray.an.feature.kernel.model.KernelSettings
import com.simplexray.an.feature.kernel.model.SniffProtocol
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.settings.LabSettingDetails
import com.simplexray.an.ui.components.settings.LabSettingDivider
import com.simplexray.an.ui.components.settings.LabSettingGroup
import com.simplexray.an.ui.components.settings.LabSettingNote
import com.simplexray.an.ui.components.settings.LabSettingToggle
import com.simplexray.an.ui.components.settings.LabSettingValue

@Composable
internal fun KernelSettingsSection(prefs: Preferences) {
    var stored by remember(prefs) { mutableStateOf(prefs.kernelSettingsJson) }
    val settings = remember(stored) { runCatching { KernelSettings.decode(stored) }.getOrNull() }
    var editing by remember { mutableStateOf<KernelField?>(null) }
    var choice by remember { mutableStateOf<KernelChoice?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    fun persist(json: String?): Boolean {
        prefs.kernelSettingsJson = json
        if (prefs.kernelSettingsJson != json) {
            saveError = "Не удалось сохранить настройки ядра. Попробуй ещё раз."
            return false
        }
        stored = json
        saveError = null
        return true
    }

    fun save(updated: KernelSettings): Boolean {
        val json =
            try {
                updated.encode()
            } catch (invalid: IllegalArgumentException) {
                saveError = invalid.message
                return false
            }
        return persist(json)
    }

    fun edit(field: KernelField) {
        saveError = null
        editing = field
    }

    fun select(field: KernelChoice) {
        saveError = null
        choice = field
    }

    if (saveError != null && editing == null && choice == null)
        LabSettingNote(saveError!!, warning = true)
    if (settings == null) {
        LabSettingGroup("Настройки ядра") {
            LabSettingNote(
                "Настройки ядра повреждены. Сбрось их к значениям по умолчанию.",
                warning = true,
            )
            LabButton(
                "Сбросить настройки ядра",
                { persist(null) },
                Modifier.fillMaxWidth(),
                danger = true,
                icon = "reset",
            )
        }
        return
    }

    LabSettingDetails("Sniffing · определение доменов", initiallyExpanded = true) {
        LabSettingToggle(
            "Sniffing",
            "Определяет домен по трафику; не влияет на FakeIP.",
            settings.sniffingEnabled,
            { save(settings.copy(sniffingEnabled = it)) },
        )
        LabSettingDivider()
        LabSettingToggle(
            "Только для маршрутизации",
            "Использует найденный домен только в правилах маршрута.",
            settings.sniffingRouteOnly,
            { save(settings.copy(sniffingRouteOnly = it)) },
        )
        LabSettingDivider()
        LabSettingValue(
            "Протоколы",
            SniffProtocol.entries
                .filter { it in settings.sniffingProtocols }
                .joinToString { it.name }
                .ifEmpty { "Не выбраны" },
            "Выбирает типы трафика для определения домена.",
            modifier = Modifier.semantics { contentDescription = "Протоколы" },
        ) {
            select(KernelChoice.PROTOCOLS)
        }
    }
    LabSettingDetails("Mux / XUDP") {
        LabSettingToggle(
            "Мультиплексирование",
            "Объединяет соединения при поддержке сервера; без WireGuard.",
            settings.muxEnabled,
            { save(settings.copy(muxEnabled = it)) },
        )
        LabSettingDivider()
        KernelValue(KernelField.MUX, settings) { edit(KernelField.MUX) }
        LabSettingDivider()
        KernelValue(KernelField.XUDP, settings) { edit(KernelField.XUDP) }
        LabSettingDivider()
        LabSettingValue(
            "UDP / 443",
            settings.udp443.label(),
            "Разрешает, блокирует или выводит UDP на порту 443 из Mux.",
            modifier = Modifier.semantics { contentDescription = "UDP / 443" },
        ) {
            select(KernelChoice.UDP443)
        }
    }
    LabSettingDetails("Sockopt · параметры сокета") {
        LabSettingValue(
            "Разрешение адреса сервера",
            settings.serverDomainStrategy.configValue,
            "DNS сервера идёт напрямую; AsIs оставляет выбор транспорту.",
            modifier = Modifier.semantics { contentDescription = "Разрешение адреса сервера" },
        ) {
            select(KernelChoice.DOMAIN_STRATEGY)
        }
        LabSettingDivider()
        LabSettingToggle(
            "TCP Fast Open",
            "Ускоряет начало TCP при поддержке ОС и сервера.",
            settings.tcpFastOpen,
            { save(settings.copy(tcpFastOpen = it)) },
        )
        LabSettingDivider()
        KernelValue(KernelField.KEEP_ALIVE, settings) { edit(KernelField.KEEP_ALIVE) }
        LabSettingDivider()
        KernelValue(KernelField.USER_TIMEOUT, settings) { edit(KernelField.USER_TIMEOUT) }
        LabSettingDivider()
        LabSettingValue(
            "Алгоритм TCP",
            settings.tcpCongestion.label(),
            "Управляет скоростью TCP; доступность зависит от ядра ОС.",
            modifier = Modifier.semantics { contentDescription = "Алгоритм TCP" },
        ) {
            select(KernelChoice.CONGESTION)
        }
    }
    LabSettingDetails("Policy · лимиты соединения") {
        listOf(
                KernelField.HANDSHAKE,
                KernelField.IDLE,
                KernelField.UPLINK,
                KernelField.DOWNLINK,
                KernelField.BUFFER,
            )
            .forEachIndexed { index, field ->
                if (index > 0) LabSettingDivider()
                KernelValue(field, settings) { edit(field) }
            }
    }

    editing?.let { field ->
        KernelFieldEditor(
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
        KernelChoiceDialog(
            selection,
            settings,
            saveError,
            onDismiss = {
                choice = null
                saveError = null
            },
            onSave = { updated -> save(updated) },
        )
    }
}
