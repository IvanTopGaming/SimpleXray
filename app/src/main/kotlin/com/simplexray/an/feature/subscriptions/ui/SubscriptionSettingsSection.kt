package com.simplexray.an.feature.subscriptions.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.subscriptions.data.SubscriptionRefreshScheduler
import com.simplexray.an.feature.subscriptions.model.SubscriptionUpdateInterval
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.settings.LabSettingDivider
import com.simplexray.an.ui.components.settings.LabSettingGroup
import com.simplexray.an.ui.components.settings.LabSettingToggle
import com.simplexray.an.ui.components.settings.LabSettingValue

private fun SubscriptionUpdateInterval.label(): String =
    when (this) {
        SubscriptionUpdateInterval.FIFTEEN_MINUTES -> "Каждые 15 минут"
        SubscriptionUpdateInterval.ONE_HOUR -> "Каждый час"
        SubscriptionUpdateInterval.SIX_HOURS -> "Каждые 6 часов"
        SubscriptionUpdateInterval.ONE_DAY -> "Раз в сутки"
    }

@Composable
internal fun SubscriptionSettingsSection(prefs: Preferences) {
    val context = LocalContext.current
    var autoUpdate by remember(prefs) { mutableStateOf(prefs.autoUpdateSubscriptions) }
    var sendHwid by remember(prefs) { mutableStateOf(prefs.subscriptionSendHwid) }
    var interval by
        remember(prefs) {
            mutableStateOf(
                SubscriptionUpdateInterval.fromMinutes(prefs.subscriptionUpdateIntervalMinutes)
            )
        }
    var choosingInterval by remember { mutableStateOf(false) }

    LabSettingGroup("Подписки") {
        LabSettingToggle(
            "Автообновление подписок",
            "Обновлять подписки в фоне при доступной сети.",
            autoUpdate,
            {
                prefs.autoUpdateSubscriptions = it
                autoUpdate = it
                SubscriptionRefreshScheduler.reconcile(context)
            },
        )
        LabSettingDivider()
        LabSettingValue(
            "Интервал обновления",
            interval.label(),
            "Как часто запрашивать свежие данные подписок.",
            enabled = autoUpdate,
        ) {
            choosingInterval = true
        }
        LabSettingDivider()
        LabSettingToggle(
            "Отправлять HWID",
            "Передавать ID установки для привязки подписок.",
            sendHwid,
            {
                prefs.subscriptionSendHwid = it
                sendHwid = it
            },
        )
        LabSettingDivider()
        SubscriptionClientIdentitySettings(prefs)
    }

    if (choosingInterval) {
        LabDialog(
            "Интервал обновления",
            { choosingInterval = false },
            actions = {
                LabButton(
                    "Закрыть",
                    { choosingInterval = false },
                    Modifier.fillMaxWidth(),
                    icon = "close",
                )
            },
        ) {
            Column(Modifier.selectableGroup()) {
                SubscriptionUpdateInterval.entries.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = interval == choice,
                                role = Role.RadioButton,
                                onClick = {
                                    prefs.subscriptionUpdateIntervalMinutes = choice.minutes
                                    interval = choice
                                    choosingInterval = false
                                    SubscriptionRefreshScheduler.reconcile(context)
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = interval == choice, onClick = null)
                        Text(
                            choice.label(),
                            Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
