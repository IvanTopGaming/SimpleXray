package com.simplexray.an.feature.dns.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.simplexray.an.feature.dns.model.DnsQueryStrategy
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.LabField
import com.simplexray.an.ui.components.settings.LabSettingDescription
import com.simplexray.an.ui.components.settings.LabSettingValue

@Composable
internal fun DnsValue(field: DnsField, settings: DnsSettings, onClick: () -> Unit) {
    LabSettingValue(
        field.label,
        field.value(settings).ifEmpty { "Не задан" },
        field.help,
        modifier = Modifier.semantics { contentDescription = field.label },
        onClick = onClick,
    )
}

@Composable
internal fun DnsFieldEditor(
    field: DnsField,
    settings: DnsSettings,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (DnsSettings) -> Unit,
) {
    var value by remember(field, settings) { mutableStateOf(field.value(settings)) }
    var bootstrap by
        remember(field, settings) {
            mutableStateOf(
                when (field) {
                    DnsField.PRIMARY -> settings.primaryBootstrap
                    DnsField.DIRECT -> settings.directBootstrap
                    else -> settings.fallbackBootstrap
                }
            )
        }
    var error by remember(field, settings) { mutableStateOf<String?>(null) }
    val resolver = field in listOf(DnsField.PRIMARY, DnsField.FALLBACK, DnsField.DIRECT)
    LabDialog(
        field.label,
        onDismiss,
        error = error ?: saveError,
        actions = {
            LabButton("Отмена", onDismiss, Modifier.weight(1f), icon = "close")
            LabButton(
                "Сохранить",
                {
                    val updated =
                        when (field) {
                            DnsField.PRIMARY ->
                                settings.copy(
                                    primaryDns = value.trim(),
                                    primaryBootstrap = bootstrap.trim(),
                                )
                            DnsField.FALLBACK ->
                                settings.copy(
                                    fallbackDns = value.trim(),
                                    fallbackBootstrap = bootstrap.trim(),
                                )
                            DnsField.DIRECT ->
                                settings.copy(
                                    directDns = value.trim(),
                                    directBootstrap = bootstrap.trim(),
                                )
                            DnsField.DIRECT_BOOTSTRAP ->
                                settings.copy(directBootstrap = value.trim())
                            DnsField.PRIMARY_BOOTSTRAP ->
                                settings.copy(primaryBootstrap = value.trim())
                            DnsField.FALLBACK_BOOTSTRAP ->
                                settings.copy(fallbackBootstrap = value.trim())
                        }
                    try {
                        if (field == DnsField.FALLBACK || field == DnsField.FALLBACK_BOOTSTRAP)
                            updated.copy(fallbackEnabled = true).validate()
                        else updated.validate()
                        onSave(updated)
                    } catch (invalid: IllegalArgumentException) {
                        error = invalid.message
                    }
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
            Modifier.fillMaxWidth().semantics { contentDescription = field.label },
            field.label,
            keyboardOptions =
                KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
        )
        LabSettingDescription(field.help)
        if (resolver) {
            LabField(
                bootstrap,
                {
                    bootstrap = it
                    error = null
                },
                Modifier.fillMaxWidth().semantics { contentDescription = "Bootstrap IP" },
                "Bootstrap IP",
                keyboardOptions =
                    KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            )
            LabSettingDescription(DnsField.PRIMARY_BOOTSTRAP.help)
        }
    }
}

internal enum class DnsField(val label: String, val help: String) {
    DIRECT("DNS напрямую", "Резолвит прямые домены без прокси. По умолчанию Яндекс."),
    DIRECT_BOOTSTRAP(
        "Bootstrap прямого DNS",
        "IP прямого DoH без порта; нужен для адреса с доменом.",
    ),
    PRIMARY("Основной DNS", "Ищет IP по домену; адрес — IP, udp://IP:порт или HTTPS."),
    FALLBACK("Резервный адрес", "Адрес DNS на случай сбоя: IP, UDP или HTTPS для DoH."),
    PRIMARY_BOOTSTRAP(
        "Bootstrap основного DNS",
        "IP сервера DoH без порта; обязателен для адреса с доменом.",
    ),
    FALLBACK_BOOTSTRAP(
        "Bootstrap резервного DNS",
        "IP резервного DoH без порта; обязателен для адреса с доменом.",
    );

    fun value(settings: DnsSettings): String =
        when (this) {
            DIRECT -> settings.directDns
            DIRECT_BOOTSTRAP -> settings.directBootstrap
            PRIMARY -> settings.primaryDns
            FALLBACK -> settings.fallbackDns
            PRIMARY_BOOTSTRAP -> settings.primaryBootstrap
            FALLBACK_BOOTSTRAP -> settings.fallbackBootstrap
        }
}

internal fun DnsQueryStrategy.label(): String =
    when (this) {
        DnsQueryStrategy.AUTO -> "Авто"
        DnsQueryStrategy.IPV4 -> "Только IPv4"
        DnsQueryStrategy.IPV6 -> "Только IPv6"
    }
