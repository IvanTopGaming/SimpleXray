package com.simplexray.an.feature.kernel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.kernel.model.KernelSettings
import com.simplexray.an.feature.kernel.model.ServerDomainStrategy
import com.simplexray.an.feature.kernel.model.SniffProtocol
import com.simplexray.an.feature.kernel.model.TcpCongestion
import com.simplexray.an.feature.kernel.model.Udp443Mode
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabDialog
import com.simplexray.an.ui.components.settings.LabSettingToggle

@Composable
internal fun KernelChoiceDialog(
    selection: KernelChoice,
    settings: KernelSettings,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (KernelSettings) -> Boolean,
) {
    LabDialog(
        selection.label,
        { onDismiss() },
        error = saveError,
        actions = { LabButton("Закрыть", { onDismiss() }, Modifier.fillMaxWidth(), icon = "close") },
    ) {
        if (selection == KernelChoice.PROTOCOLS) {
            SniffProtocol.entries.forEach { protocol ->
                LabSettingToggle(
                    protocol.name,
                    when (protocol) {
                        SniffProtocol.HTTP -> "Определяет домен из заголовка HTTP-запроса."
                        SniffProtocol.TLS -> "Определяет домен из имени сервера в TLS."
                        SniffProtocol.QUIC -> "Определяет домен при установке QUIC-соединения."
                    },
                    protocol in settings.sniffingProtocols,
                    { enabled ->
                        onSave(
                            settings.copy(
                                sniffingProtocols =
                                    if (enabled) settings.sniffingProtocols + protocol
                                    else settings.sniffingProtocols - protocol
                            )
                        )
                    },
                )
            }
        } else {
            val labels =
                when (selection) {
                    KernelChoice.UDP443 -> Udp443Mode.entries.map { it.label() }
                    KernelChoice.DOMAIN_STRATEGY ->
                        ServerDomainStrategy.entries.map { it.configValue }
                    KernelChoice.CONGESTION -> TcpCongestion.entries.map { it.label() }
                    KernelChoice.PROTOCOLS -> emptyList()
                }
            val selected =
                when (selection) {
                    KernelChoice.UDP443 -> settings.udp443.ordinal
                    KernelChoice.DOMAIN_STRATEGY -> settings.serverDomainStrategy.ordinal
                    KernelChoice.CONGESTION -> settings.tcpCongestion.ordinal
                    KernelChoice.PROTOCOLS -> -1
                }
            labels.forEachIndexed { index, label ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        val updated =
                            when (selection) {
                                KernelChoice.UDP443 ->
                                    settings.copy(udp443 = Udp443Mode.entries[index])
                                KernelChoice.DOMAIN_STRATEGY ->
                                    settings.copy(
                                        serverDomainStrategy = ServerDomainStrategy.entries[index]
                                    )
                                KernelChoice.CONGESTION ->
                                    settings.copy(tcpCongestion = TcpCongestion.entries[index])
                                KernelChoice.PROTOCOLS -> settings
                            }
                        if (onSave(updated)) onDismiss()
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
