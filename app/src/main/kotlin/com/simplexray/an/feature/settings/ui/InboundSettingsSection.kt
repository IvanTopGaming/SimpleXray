package com.simplexray.an.feature.settings.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.simplexray.an.R
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.feature.settings.state.SettingsState
import com.simplexray.an.ui.components.settings.LabEditableSetting
import com.simplexray.an.ui.components.settings.LabSettingDivider
import com.simplexray.an.ui.components.settings.LabSettingGroup
import com.simplexray.an.ui.components.settings.LabSettingToggle
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboundSettingsSection(
    mainViewModel: MainViewModel,
    settingsState: SettingsState,
    vpnDisabled: Boolean,
    sheetState: SheetState,
    scope: CoroutineScope,
) {
    LabSettingGroup("Локальный SOCKS") {
        LabEditableSetting(
            "Адрес прослушивания",
            settingsState.socksAddress,
            mainViewModel::updateSocksAddress,
            sheetState,
            scope,
            help = "IP, на котором доступен локальный прокси.",
            keyboardType = KeyboardType.Uri,
        )
        LabSettingDivider()
        LabEditableSetting(
            "Порт SOCKS",
            settingsState.socksPort,
            mainViewModel::updateSocksPort,
            sheetState,
            scope,
            help = "Порт для подключения по SOCKS.",
            keyboardType = KeyboardType.Number,
        )
        LabSettingDivider()
        LabSettingToggle(
            "UDP",
            "Разрешает UDP-трафик. DNS работает в любом случае.",
            settingsState.switches.socksUdpEnabled,
            mainViewModel::setSocksUdpEnabled,
        )
    }
    LabSettingGroup("Локальный HTTP") {
        LabSettingToggle(
            "HTTP-прокси",
            "Включает локальный вход для HTTP CONNECT.",
            settingsState.switches.httpProxyEnabled,
            mainViewModel::setHttpProxyEnabled,
        )
        LabSettingDivider()
        LabEditableSetting(
            "Порт HTTP",
            settingsState.httpPort,
            mainViewModel::updateHttpPort,
            sheetState,
            scope,
            help = "Отдельный порт для HTTP-прокси.",
            keyboardType = KeyboardType.Number,
        )
    }
    LabSettingGroup("Доступ из сети") {
        LabSettingToggle(
            "Разрешить доступ из локальной сети",
            "Разрешает сетевой IP; выключение вернёт 127.0.0.1.",
            settingsState.switches.allowLanAccess,
            mainViewModel::setAllowLanAccess,
        )
    }
    LabSettingGroup("Авторизация SOCKS и HTTP") {
        LabEditableSetting(
            stringResource(R.string.socks_user),
            settingsState.socksUser,
            mainViewModel::updateSocksUser,
            sheetState,
            scope,
            help = "Имя для входа в локальный SOCKS и HTTP.",
        )
        LabSettingDivider()
        LabEditableSetting(
            stringResource(R.string.socks_pass),
            settingsState.socksPass,
            mainViewModel::updateSocksPass,
            sheetState,
            scope,
            help = "Пароль для входа в локальный SOCKS и HTTP.",
            keyboardType = KeyboardType.Password,
        )
    }
}
