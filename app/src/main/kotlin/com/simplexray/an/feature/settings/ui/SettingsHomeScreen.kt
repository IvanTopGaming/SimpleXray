package com.simplexray.an.feature.settings.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.labGap
import com.simplexray.an.ui.components.labHorizontalPadding
import com.simplexray.an.ui.components.settings.LabSettingDescription
import com.simplexray.an.ui.theme.ThemeMode

private data class SettingsDestination(
    val title: String,
    val subtitle: String,
    val icon: String,
    val enabled: Boolean = true,
)

@Composable
fun SettingsHomeScreen(
    mainViewModel: MainViewModel,
    onSection: (String) -> Unit,
    onLogs: () -> Unit,
) {
    val settings by mainViewModel.settingsState.collectAsState()
    var themeMenu by remember { mutableStateOf(false) }
    val sections =
        listOf(
            SettingsDestination("Подключение", "VPN, IPv6 и проверка сети", "power"),
            SettingsDestination("DNS", "Резолверы, кэш и стратегия запросов", "servers"),
            SettingsDestination("Роутинг", "Правила, приоритеты и исключения", "route"),
            SettingsDestination("Приложения", "Кто использует VPN", "home"),
            SettingsDestination("Подписки", "HWID и обновление", "subs"),
            SettingsDestination("Пинг", "TCP, HTTP GET и HTTP HEAD", "power"),
            SettingsDestination("Входящие подключения", "Локальный SOCKS и HTTP-прокси", "subs"),
            SettingsDestination("Ядро Xray", "Sniffing, Mux, Sockopt и лимиты", "settings"),
            SettingsDestination("Базы маршрутизации", "GeoIP, GeoSite и источники", "servers"),
            SettingsDestination("Профиль и JSON", "Предпросмотр и переопределения", "edit"),
            SettingsDestination("Журнал и отладка", "Последние события соединения", "info"),
        )
    val openSection: (SettingsDestination) -> Unit = { section ->
        when (section.title) {
            "Журнал и отладка" -> onLogs()
            "Приложения" -> mainViewModel.navigateToAppList()
            "Ядро Xray" -> onSection("Ядро и конфигурация")
            else -> onSection(section.title)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = labHorizontalPadding(), vertical = labGap()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "main-settings", contentType = "settings-menu") {
            SettingsMenu(sections.take(4), openSection)
        }
        item(key = "network-settings", contentType = "settings-menu") {
            SettingsMenu(sections.drop(4), openSection)
        }
        item(key = "appearance", contentType = "appearance") {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp)) {
                    Text(
                        "ОФОРМЛЕНИЕ",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Тема", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    LabSettingDescription("Цветовая схема приложения.")
                    Spacer(Modifier.height(10.dp))
                    Box {
                        Surface(
                            onClick = { themeMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Row(
                                modifier =
                                    Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    when (settings.switches.themeMode) {
                                        ThemeMode.Auto -> "Системная"
                                        ThemeMode.Light -> "Светлая"
                                        ThemeMode.Dark -> "Тёмная"
                                    },
                                    modifier = Modifier.weight(1f),
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                                )
                                LabIcon("arrow", modifier = Modifier.size(16.dp).rotate(90f))
                            }
                        }
                        DropdownMenu(
                            expanded = themeMenu,
                            onDismissRequest = { themeMenu = false },
                        ) {
                            listOf(
                                    ThemeMode.Auto to "Системная",
                                    ThemeMode.Light to "Светлая",
                                    ThemeMode.Dark to "Тёмная",
                                )
                                .forEach { (mode, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        leadingIcon = {
                                            LabIcon(
                                                when (mode) {
                                                    ThemeMode.Auto -> "settings"
                                                    ThemeMode.Light -> "sun"
                                                    ThemeMode.Dark -> "moon"
                                                },
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        onClick = {
                                            themeMenu = false
                                            mainViewModel.setTheme(mode)
                                        },
                                    )
                                }
                        }
                    }
                }
            }
        }
        item(key = "tools", contentType = "settings-menu") {
            SettingsMenu(
                listOf(SettingsDestination("О приложении", "Версия приложения и ядра", "info"))
            ) { section ->
                onSection(section.title)
            }
        }
    }
}

@Composable
private fun SettingsMenu(
    sections: List<SettingsDestination>,
    onSection: (SettingsDestination) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(1.dp)) {
            sections.forEachIndexed { index, section ->
                SettingsEntry(section.title, section.subtitle, section.icon, section.enabled) {
                    onSection(section)
                }
                if (index < sections.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String,
    icon: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { if (!enabled) stateDescription = "Пока недоступно" },
        color = MaterialTheme.colorScheme.surface,
        contentColor =
            if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabIcon(icon, modifier = Modifier.size(21.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight(550),
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                )
                Text(
                    subtitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LabIcon("arrow", modifier = Modifier.size(21.dp))
        }
    }
}
