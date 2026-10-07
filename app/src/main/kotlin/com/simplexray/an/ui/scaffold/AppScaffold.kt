package com.simplexray.an.ui.scaffold

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.simplexray.an.feature.routing.ui.RoutingPresetMenu
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.RightAlignedMenu
import com.simplexray.an.ui.components.labHorizontalPadding
import com.simplexray.an.ui.navigation.ROUTE_APP_LIST
import com.simplexray.an.ui.navigation.ROUTE_CONFIG
import com.simplexray.an.ui.navigation.ROUTE_LOG
import com.simplexray.an.ui.navigation.ROUTE_SETTINGS
import com.simplexray.an.ui.navigation.ROUTE_STATS
import com.simplexray.an.ui.navigation.ROUTE_SUBSCRIPTIONS

@Composable
fun AppScaffold(
    navController: NavHostController,
    entry: NavBackStackEntry?,
    snackbarHostState: SnackbarHostState,
    onAdd: () -> Unit,
    onPaste: () -> Unit,
    onExportLog: () -> Unit,
    onImportRoutingPreset: () -> Unit = {},
    onExportRoutingPreset: () -> Unit = {},
    routingPresetBusy: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val route = entry?.destination?.route ?: ROUTE_STATS
    val tabs =
        listOf(
            Triple(ROUTE_STATS, "Главная", "home"),
            Triple(ROUTE_CONFIG, "Серверы", "servers"),
            Triple(ROUTE_SUBSCRIPTIONS, "Подписки", "subs"),
            Triple(ROUTE_SETTINGS, "Настройки", "settings"),
        )
    val title =
        tabs.find { it.first == route }?.second
            ?: when (route) {
                ROUTE_LOG -> "Журнал и отладка"
                ROUTE_APP_LIST -> "Приложения"
                else ->
                    entry?.arguments?.getString("section")?.let {
                        if (it == "Ядро и конфигурация") "Ядро Xray" else it
                    } ?: "Настройки"
            }
    val narrow = LocalConfiguration.current.screenWidthDp <= 370
    val inset = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    val headerTop = maxOf(inset, if (narrow) 48.dp else 52.dp)
    var expanded by remember(route) { mutableStateOf(false) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                Modifier.fillMaxWidth()
                    .padding(
                        top = headerTop,
                        start = labHorizontalPadding(),
                        end = labHorizontalPadding(),
                    )
                    .heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 6.dp else 10.dp),
            ) {
                if (tabs.none { it.first == route }) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        LabIcon("arrow", "Назад", Modifier.rotate(180f))
                    }
                }
                Text(
                    title,
                    style =
                        MaterialTheme.typography.titleLarge.copy(
                            fontSize =
                                if (narrow || tabs.none { it.first == route }) 21.sp else 22.sp
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (route == ROUTE_CONFIG || route == ROUTE_SUBSCRIPTIONS) {
                    Box {
                        LabButton(
                            "Добавить",
                            onClick = { expanded = !expanded },
                            modifier = Modifier.semantics { contentDescription = "Добавить" },
                            icon = "add",
                            compact = true,
                        )
                        RightAlignedMenu(expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Вручную") },
                                leadingIcon = {
                                    LabIcon(
                                        "edit",
                                        modifier = Modifier.testTag("manual-import-icon"),
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    onAdd()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Из буфера") },
                                leadingIcon = {
                                    LabIcon(
                                        "clipboard",
                                        modifier = Modifier.testTag("clipboard-import-icon"),
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    onPaste()
                                },
                            )
                        }
                    }
                }
                if (
                    route == "settings-detail/{section}" &&
                        entry?.arguments?.getString("section") == "Роутинг"
                ) {
                    RoutingPresetMenu(
                        onImport = onImportRoutingPreset,
                        onExport = onExportRoutingPreset,
                        busy = routingPresetBusy,
                    )
                }
                if (route == ROUTE_LOG)
                    LabButton("Отправить", onClick = onExportLog, icon = "send", compact = true)
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    tabs.forEach { (destination, label, icon) ->
                        val active =
                            route == destination ||
                                (destination == ROUTE_SETTINGS && tabs.none { it.first == route })
                        val color =
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        Column(
                            Modifier.weight(1f)
                                .heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (active) MaterialTheme.colorScheme.surfaceContainer
                                    else MaterialTheme.colorScheme.background
                                )
                                .selectable(
                                    active,
                                    role = Role.Tab,
                                    onClick = { navigateToRoute(navController, destination) },
                                )
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement =
                                Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                        ) {
                            LabIcon(icon, tint = color)
                            Text(
                                label,
                                color = color,
                                maxLines = 1,
                                style =
                                    MaterialTheme.typography.labelSmall.copy(
                                        fontSize = if (narrow) 11.sp else 12.sp,
                                        fontWeight =
                                            if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    ),
                            )
                        }
                    }
                }
            }
        },
        content = content,
    )
}

fun navigateToRoute(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
