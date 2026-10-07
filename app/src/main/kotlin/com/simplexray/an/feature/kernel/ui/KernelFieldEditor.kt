package com.simplexray.an.feature.kernel.ui

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
import com.simplexray.an.feature.kernel.model.KernelSettings
import com.simplexray.an.feature.kernel.model.TcpCongestion
import com.simplexray.an.feature.kernel.model.Udp443Mode
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.LabField
import com.simplexray.an.ui.components.settings.LabSettingDescription
import com.simplexray.an.ui.components.settings.LabSettingValue

@Composable
internal fun KernelValue(field: KernelField, settings: KernelSettings, onClick: () -> Unit) {
    LabSettingValue(
        field.label,
        field.value(settings)?.toString() ?: "Значение ядра",
        field.help,
        modifier = Modifier.semantics { contentDescription = field.label },
        onClick = onClick,
    )
}

@Composable
internal fun KernelFieldEditor(
    field: KernelField,
    settings: KernelSettings,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (KernelSettings) -> Unit,
) {
    var value by
        remember(field, settings) { mutableStateOf(field.value(settings)?.toString().orEmpty()) }
    var error by remember(field, settings) { mutableStateOf<String?>(null) }
    LabDialog(
        field.label,
        onDismiss,
        error = error ?: saveError,
        actions = {
            LabButton("Отмена", onDismiss, Modifier.weight(1f), icon = "close")
            LabButton(
                "Сохранить",
                {
                    val text = value.trim()
                    val number = text.toIntOrNull()
                    if (text.isEmpty() && field.nullable) {
                        onSave(field.update(settings, null))
                    } else if (number == null || number !in field.range) {
                        error = "Укажи целое число от ${field.range.first} до ${field.range.last}."
                    } else {
                        onSave(field.update(settings, number))
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
            Modifier.fillMaxWidth(),
            field.label,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType =
                        if (field.range.first < 0) KeyboardType.Ascii else KeyboardType.Number
                ),
        )
        LabSettingDescription(field.help)
        if (field.nullable) {
            LabButton(
                "Сбросить поле",
                {
                    value = ""
                    error = null
                },
                Modifier.fillMaxWidth(),
                icon = "reset",
            )
        }
    }
}

internal enum class KernelChoice(val label: String) {
    PROTOCOLS("Протоколы"),
    UDP443("UDP / 443"),
    DOMAIN_STRATEGY("Разрешение адреса сервера"),
    CONGESTION("Алгоритм TCP"),
}

internal enum class KernelField(
    val label: String,
    val range: IntRange,
    val nullable: Boolean,
    val help: String,
) {
    MUX(
        "Mux · TCP concurrency",
        -1..128,
        false,
        "TCP-потоков в канале: −1 — выкл., 0 — ядро. Для Vision −1.",
    ),
    XUDP(
        "XUDP concurrency",
        -1..1024,
        false,
        "UDP-потоков в канале: −1 — без XUDP, 0 — выбор ядра.",
    ),
    KEEP_ALIVE("TCP keep-alive, с", 0..3600, false, "Интервал проверки TCP, с; 0 — значение ядра."),
    USER_TIMEOUT(
        "TCP user timeout, мс",
        0..600000,
        false,
        "Ожидание подтверждения TCP, мс; 0 — значение ядра.",
    ),
    HANDSHAKE(
        "Handshake, с",
        1..600,
        true,
        "Время на установку соединения, с; пусто — значение ядра.",
    ),
    IDLE(
        "Простой соединения, с",
        1..86400,
        true,
        "Закрывает соединение после простоя, с; пусто — значение ядра.",
    ),
    UPLINK(
        "Uplink only, с",
        0..600,
        true,
        "Лимит отправки после конца приёма, с; пусто — значение ядра.",
    ),
    DOWNLINK(
        "Downlink only, с",
        0..600,
        true,
        "Лимит приёма после конца отправки, с; пусто — значение ядра.",
    ),
    BUFFER(
        "Буфер, КиБ",
        0..65536,
        true,
        "Память на соединение, КиБ; 0 — без буфера, пусто — выбор ядра.",
    );

    fun value(settings: KernelSettings): Int? =
        when (this) {
            MUX -> settings.muxConcurrency
            XUDP -> settings.xudpConcurrency
            KEEP_ALIVE -> settings.tcpKeepAliveInterval
            USER_TIMEOUT -> settings.tcpUserTimeout
            HANDSHAKE -> settings.handshake
            IDLE -> settings.connectionIdle
            UPLINK -> settings.uplinkOnly
            DOWNLINK -> settings.downlinkOnly
            BUFFER -> settings.bufferSize
        }

    fun update(settings: KernelSettings, value: Int?): KernelSettings =
        when (this) {
            MUX -> settings.copy(muxConcurrency = requireNotNull(value))
            XUDP -> settings.copy(xudpConcurrency = requireNotNull(value))
            KEEP_ALIVE -> settings.copy(tcpKeepAliveInterval = requireNotNull(value))
            USER_TIMEOUT -> settings.copy(tcpUserTimeout = requireNotNull(value))
            HANDSHAKE -> settings.copy(handshake = value)
            IDLE -> settings.copy(connectionIdle = value)
            UPLINK -> settings.copy(uplinkOnly = value)
            DOWNLINK -> settings.copy(downlinkOnly = value)
            BUFFER -> settings.copy(bufferSize = value)
        }
}

internal fun Udp443Mode.label(): String =
    when (this) {
        Udp443Mode.REJECT -> "Отклонять"
        Udp443Mode.ALLOW -> "Разрешать"
        Udp443Mode.SKIP -> "Без Mux"
    }

internal fun TcpCongestion.label(): String =
    when (this) {
        TcpCongestion.SYSTEM -> "Системный"
        TcpCongestion.CUBIC -> "CUBIC"
        TcpCongestion.BBR -> "BBR"
    }
