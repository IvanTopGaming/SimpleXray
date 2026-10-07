package com.simplexray.an.feature.settings.state

import com.simplexray.an.feature.servers.model.ProbeMethod

data class SettingsState(
    val socksAddress: InputFieldState,
    val socksPort: InputFieldState,
    val socksUser: InputFieldState,
    val socksPass: InputFieldState,
    val dnsIpv4: InputFieldState,
    val dnsIpv6: InputFieldState,
    val switches: SwitchStates,
    val info: InfoStates,
    val files: FileStates,
    val connectivityTestTarget: InputFieldState,
    val connectivityTestTimeout: InputFieldState,
    val httpPort: InputFieldState = InputFieldState("10809"),
    val probeMethod: ProbeMethod = ProbeMethod.HTTP_GET,
)
