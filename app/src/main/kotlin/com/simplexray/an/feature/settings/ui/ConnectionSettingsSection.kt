package com.simplexray.an.feature.settings.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.feature.settings.state.SettingsState
import com.simplexray.an.ui.components.settings.LabEditableSetting
import com.simplexray.an.ui.components.settings.LabSettingDivider
import com.simplexray.an.ui.components.settings.LabSettingGroup
import com.simplexray.an.ui.components.settings.LabSettingToggle
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionSettingsSection(
    mainViewModel: MainViewModel,
    settingsState: SettingsState,
    vpnDisabled: Boolean,
    sheetState: SheetState,
    scope: CoroutineScope,
) {
    LabSettingGroup("Режим работы") {
        LabSettingToggle(
            "Только локальный прокси",
            "Локальный прокси без VPN и выбора приложений.",
            settingsState.switches.disableVpn,
            mainViewModel::setDisableVpnEnabled,
        )
        LabSettingDivider()
        LabSettingToggle(
            "IPv6",
            "Разрешает IPv6-соединения через VPN.",
            settingsState.switches.ipv6Enabled,
            mainViewModel::setIpv6Enabled,
            !vpnDisabled,
        )
    }
    LabSettingGroup("Автозапуск") {
        LabSettingToggle(
            "Автозапуск и подключение",
            "Подключается после перезагрузки и разблокировки телефона.",
            settingsState.switches.autoStartEnabled,
            mainViewModel::setAutoStartEnabled,
        )
    }
    LabSettingGroup("DNS интерфейса") {
        LabEditableSetting(
            "DNS · IPv4",
            settingsState.dnsIpv4,
            mainViewModel::updateDnsIpv4,
            sheetState,
            scope,
            help = "DNS-адрес IPv4, который VPN сообщает Android.",
            enabled = !vpnDisabled,
            keyboardType = KeyboardType.Uri,
        )
        LabSettingDivider()
        LabEditableSetting(
            "DNS · IPv6",
            settingsState.dnsIpv6,
            mainViewModel::updateDnsIpv6,
            sheetState,
            scope,
            help = "DNS-адрес IPv6, который VPN сообщает Android.",
            enabled = settingsState.switches.ipv6Enabled && !vpnDisabled,
            keyboardType = KeyboardType.Uri,
        )
    }
}
