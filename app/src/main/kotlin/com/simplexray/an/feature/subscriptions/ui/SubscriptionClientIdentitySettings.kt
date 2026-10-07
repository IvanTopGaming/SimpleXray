package com.simplexray.an.feature.subscriptions.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.simplexray.an.feature.subscriptions.data.SubscriptionClientIdentity
import com.simplexray.an.feature.subscriptions.data.SubscriptionIdentityField
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.LabField
import com.simplexray.an.ui.components.settings.LabSettingDescription
import com.simplexray.an.ui.components.settings.LabSettingNote
import com.simplexray.an.ui.components.settings.LabSettingValue
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SubscriptionClientIdentitySettings(prefs: Preferences) {
    var opened by remember { mutableStateOf(false) }
    LabSettingValue(
        "Данные клиента",
        "HWID, устройство и User-Agent",
        "Общий профиль для всех подписок при обновлении.",
    ) {
        opened = true
    }
    if (opened) SubscriptionClientIdentityDialog(prefs) { opened = false }
}

@Composable
private fun SubscriptionClientIdentityDialog(prefs: Preferences, onDismiss: () -> Unit) {
    var stored by remember(prefs) { mutableStateOf(prefs.subscriptionClientIdentityJson) }
    val identity =
        remember(stored) { runCatching { SubscriptionClientIdentity.parse(stored) }.getOrNull() }
    var editing by remember { mutableStateOf<SubscriptionIdentityField?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val installationId by
        produceState<String?>(null, prefs) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { prefs.subscriptionInstallationId }.getOrNull()
                }
        }
    val defaults =
        remember(installationId) {
            SubscriptionClientIdentity.deviceDefaults(installationId.orEmpty())
        }
    val effective = remember(identity, defaults) { identity?.withDefaults(defaults) }

    fun save(updated: SubscriptionClientIdentity): Boolean {
        val json = updated.encode()
        prefs.subscriptionClientIdentityJson = json
        if (prefs.subscriptionClientIdentityJson != json) {
            saveError = "Не удалось сохранить данные клиента. Попробуй ещё раз."
            return false
        }
        stored = json
        saveError = null
        return true
    }

    val field = editing
    if (field != null && identity != null) {
        SubscriptionIdentityEditor(
            field,
            identity,
            defaults[field],
            saveError,
            onDismiss = {
                editing = null
                saveError = null
            },
            onSave = { if (save(it)) editing = null },
        )
    } else {
        LabDialog(
            "Данные клиента",
            onDismiss,
            error = saveError,
            actions = { LabButton("Закрыть", onDismiss, Modifier.fillMaxWidth(), icon = "close") },
        ) {
            if (identity == null) {
                LabSettingNote(
                    "Данные повреждены. Сбрось их к значениям по умолчанию.",
                    warning = true,
                )
                LabButton(
                    "Сбросить данные клиента",
                    { save(SubscriptionClientIdentity()) },
                    Modifier.fillMaxWidth(),
                    danger = true,
                    icon = "reset",
                )
            } else {
                SubscriptionIdentityField.entries.forEach { entry ->
                    LabSettingValue(
                        entry.label,
                        effective?.get(entry).orEmpty().ifEmpty { "Загрузка…" },
                        entry.help(),
                        enabled =
                            entry != SubscriptionIdentityField.HWID ||
                                !effective?.get(entry).isNullOrEmpty(),
                        modifier = Modifier.semantics { contentDescription = entry.label },
                    ) {
                        saveError = null
                        editing = entry
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionIdentityEditor(
    field: SubscriptionIdentityField,
    identity: SubscriptionClientIdentity,
    defaultValue: String,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (SubscriptionClientIdentity) -> Unit,
) {
    var value by
        remember(field, identity) { mutableStateOf(identity[field].ifEmpty { defaultValue }) }
    var error by remember(field, identity) { mutableStateOf<String?>(null) }
    LabDialog(
        field.label,
        onDismiss,
        error = error ?: saveError,
        actions = {
            LabButton("Отмена", onDismiss, Modifier.weight(1f), icon = "close")
            LabButton(
                "Сохранить",
                {
                    val updated = identity.withValue(field, value.trim())
                    error = updated.validationError()
                    if (error == null) onSave(updated)
                },
                Modifier.weight(1f),
                primary = true,
                icon = "save",
            )
        },
    ) {
        LabField(
            value,
            {
                value = it
                error = null
            },
            Modifier.fillMaxWidth(),
            field.label,
            keyboardOptions =
                KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        )
        LabSettingDescription(field.help())
        if (field == SubscriptionIdentityField.HWID) {
            LabButton(
                "Сгенерировать",
                {
                    value = UUID.randomUUID().toString()
                    error = null
                },
                Modifier.fillMaxWidth(),
                icon = "refresh",
            )
        }
    }
}

private fun SubscriptionIdentityField.help(): String =
    when (this) {
        SubscriptionIdentityField.HWID -> "ID установки. Пусто — созданный приложением."
        SubscriptionIdentityField.MODEL -> "Модель для подписок. Пусто — модель устройства."
        SubscriptionIdentityField.OS -> "Система для подписок. Пусто — Android."
        SubscriptionIdentityField.OS_VERSION -> "Версия системы. Пусто — текущая версия Android."
        SubscriptionIdentityField.USER_AGENT ->
            "Имя клиента в запросах. Пусто — SimpleXray с версией."
    }
