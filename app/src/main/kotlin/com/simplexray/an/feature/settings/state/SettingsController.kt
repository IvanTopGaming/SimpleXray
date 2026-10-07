package com.simplexray.an.feature.settings.state

import android.app.Application
import android.util.Log
import androidx.lifecycle.application
import com.simplexray.an.BuildConfig
import com.simplexray.an.R
import com.simplexray.an.core.files.FileManager
import com.simplexray.an.feature.servers.model.ProbeMethod
import com.simplexray.an.feature.settings.model.InboundSettings
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.TProxyService
import com.simplexray.an.ui.theme.ThemeMode
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.util.regex.Pattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "MainViewModel"

internal class SettingsController(
    private val application: Application,
    private val prefs: Preferences,
    private val fileManager: FileManager,
    private val resetServerChecks: () -> Unit,
    private val reloadView: () -> Unit,
) {
    private val _settingsState =
        MutableStateFlow(
            SettingsState(
                socksAddress = InputFieldState(prefs.socksAddress),
                socksPort = InputFieldState(prefs.socksPort.toString()),
                httpPort = InputFieldState(prefs.httpPort.toString()),
                socksUser = InputFieldState(prefs.socksUsername),
                socksPass = InputFieldState(prefs.socksPassword),
                dnsIpv4 = InputFieldState(prefs.dnsIpv4),
                dnsIpv6 = InputFieldState(prefs.dnsIpv6),
                switches =
                    SwitchStates(
                        ipv6Enabled = prefs.ipv6,
                        httpProxyEnabled = prefs.httpProxyEnabled,
                        socksUdpEnabled = prefs.socksUdpEnabled,
                        allowLanAccess = prefs.allowLanAccess,
                        bypassLanEnabled = prefs.bypassLan,
                        disableVpn = prefs.disableVpn,
                        autoStartEnabled = prefs.autoStartEnabled,
                        themeMode = prefs.theme,
                    ),
                info =
                    InfoStates(
                        appVersion = BuildConfig.VERSION_NAME,
                        kernelVersion = "N/A",
                        geoipSummary = "",
                        geositeSummary = "",
                        geoipUrl = prefs.geoipUrl,
                        geositeUrl = prefs.geositeUrl,
                    ),
                files =
                    FileStates(
                        isGeoipCustom = prefs.customGeoipImported,
                        isGeositeCustom = prefs.customGeositeImported,
                    ),
                connectivityTestTarget = InputFieldState(prefs.connectivityTestTarget),
                connectivityTestTimeout =
                    InputFieldState(prefs.connectivityTestTimeout.coerceIn(1, 30000).toString()),
                probeMethod = selectedProbeMethod(),
            )
        )

    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    fun updateSettingsState() {
        _settingsState.value =
            _settingsState.value.copy(
                socksAddress = InputFieldState(prefs.socksAddress),
                socksPort = InputFieldState(prefs.socksPort.toString()),
                httpPort = InputFieldState(prefs.httpPort.toString()),
                socksUser = InputFieldState(prefs.socksUsername),
                socksPass = InputFieldState(prefs.socksPassword),
                dnsIpv4 = InputFieldState(prefs.dnsIpv4),
                dnsIpv6 = InputFieldState(prefs.dnsIpv6),
                switches =
                    SwitchStates(
                        ipv6Enabled = prefs.ipv6,
                        httpProxyEnabled = prefs.httpProxyEnabled,
                        socksUdpEnabled = prefs.socksUdpEnabled,
                        allowLanAccess = prefs.allowLanAccess,
                        bypassLanEnabled = prefs.bypassLan,
                        disableVpn = prefs.disableVpn,
                        autoStartEnabled = prefs.autoStartEnabled,
                        themeMode = prefs.theme,
                    ),
                info =
                    _settingsState.value.info.copy(
                        appVersion = BuildConfig.VERSION_NAME,
                        geoipSummary = fileManager.getRuleFileSummary("geoip.dat"),
                        geositeSummary = fileManager.getRuleFileSummary("geosite.dat"),
                        geoipUrl = prefs.geoipUrl,
                        geositeUrl = prefs.geositeUrl,
                    ),
                files =
                    FileStates(
                        isGeoipCustom = prefs.customGeoipImported,
                        isGeositeCustom = prefs.customGeositeImported,
                    ),
                connectivityTestTarget = InputFieldState(prefs.connectivityTestTarget),
                connectivityTestTimeout = InputFieldState(prefs.connectivityTestTimeout.toString()),
            )
    }

    fun loadKernelVersion() {
        val libraryDir = TProxyService.getNativeLibraryDir(application)
        val xrayPath = "$libraryDir/libxray.so"
        try {
            val process = Runtime.getRuntime().exec("$xrayPath -version")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val firstLine = reader.readLine()
            process.destroy()
            _settingsState.value =
                _settingsState.value.copy(
                    info = _settingsState.value.info.copy(kernelVersion = firstLine ?: "N/A")
                )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to get xray version", e)
            _settingsState.value =
                _settingsState.value.copy(
                    info = _settingsState.value.info.copy(kernelVersion = "N/A")
                )
        }
    }

    fun updateSocksAddress(addressString: String): Boolean {
        if (!prefs.allowLanAccess && !InboundSettings.isLoopback(addressString)) {
            _settingsState.update {
                it.copy(
                    socksAddress =
                        InputFieldState(
                            addressString,
                            "Сначала разреши доступ из локальной сети",
                            false,
                        )
                )
            }
            return false
        }
        val matcherIpv4 = IPV4_PATTERN.matcher(addressString)
        val matcherIpv6 = IPV6_PATTERN.matcher(addressString)
        return if (matcherIpv4.matches()) {
            prefs.socksAddress = addressString
            _settingsState.value =
                _settingsState.value.copy(socksAddress = InputFieldState(addressString))
            true
        } else if (matcherIpv6.matches()) {
            prefs.socksAddress = addressString
            _settingsState.value =
                _settingsState.value.copy(socksAddress = InputFieldState(addressString))
            true
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    socksAddress =
                        InputFieldState(
                            value = addressString,
                            error = application.getString(R.string.invalid_ipv4_or_ipv6),
                            isValid = false,
                        )
                )
            false
        }
    }

    fun updateSocksPort(portString: String): Boolean {
        return try {
            val port = portString.toInt()
            if (port in 1025..65535 && (!prefs.httpProxyEnabled || port != prefs.httpPort)) {
                prefs.socksPort = port
                _settingsState.value =
                    _settingsState.value.copy(socksPort = InputFieldState(portString))
                true
            } else {
                _settingsState.value =
                    _settingsState.value.copy(
                        socksPort =
                            InputFieldState(
                                value = portString,
                                error =
                                    if (port == prefs.httpPort && prefs.httpProxyEnabled)
                                        "Порты SOCKS и HTTP должны отличаться"
                                    else application.getString(R.string.invalid_port_range),
                                isValid = false,
                            )
                    )
                false
            }
        } catch (e: NumberFormatException) {
            _settingsState.value =
                _settingsState.value.copy(
                    socksPort =
                        InputFieldState(
                            value = portString,
                            error = application.getString(R.string.invalid_port),
                            isValid = false,
                        )
                )
            false
        }
    }

    fun updateSocksUser(userString: String): Boolean {
        val byteCount = userString.toByteArray(Charsets.UTF_8).size
        return if (byteCount <= 255) {
            prefs.socksUsername = userString
            _settingsState.value =
                _settingsState.value.copy(socksUser = InputFieldState(userString))
            true
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    socksUser =
                        InputFieldState(
                            value = userString,
                            error = "Username length must not exceed 255 bytes",
                            isValid = false,
                        )
                )
            false
        }
    }

    fun updateSocksPass(passString: String): Boolean {
        val byteCount = passString.toByteArray(Charsets.UTF_8).size
        return if (byteCount <= 255) {
            prefs.socksPassword = passString
            _settingsState.value =
                _settingsState.value.copy(socksPass = InputFieldState(passString))
            true
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    socksPass =
                        InputFieldState(
                            value = passString,
                            error = "Password length must not exceed 255 bytes",
                            isValid = false,
                        )
                )
            false
        }
    }

    fun updateDnsIpv4(ipv4Addr: String): Boolean {
        val matcher = IPV4_PATTERN.matcher(ipv4Addr)
        return if (matcher.matches()) {
            prefs.dnsIpv4 = ipv4Addr
            _settingsState.value = _settingsState.value.copy(dnsIpv4 = InputFieldState(ipv4Addr))
            true
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    dnsIpv4 =
                        InputFieldState(
                            value = ipv4Addr,
                            error = application.getString(R.string.invalid_ipv4),
                            isValid = false,
                        )
                )
            false
        }
    }

    fun updateDnsIpv6(ipv6Addr: String): Boolean {
        val matcher = IPV6_PATTERN.matcher(ipv6Addr)
        return if (matcher.matches()) {
            prefs.dnsIpv6 = ipv6Addr
            _settingsState.value = _settingsState.value.copy(dnsIpv6 = InputFieldState(ipv6Addr))
            true
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    dnsIpv6 =
                        InputFieldState(
                            value = ipv6Addr,
                            error = application.getString(R.string.invalid_ipv6),
                            isValid = false,
                        )
                )
            false
        }
    }

    fun updateHttpPort(value: String): Boolean {
        val port = value.toIntOrNull()
        val error =
            when {
                port == null || port !in 1025..65535 -> "Укажи порт от 1025 до 65535"
                port == prefs.socksPort -> "Порты SOCKS и HTTP должны отличаться"
                else -> null
            }
        if (error == null) prefs.httpPort = requireNotNull(port)
        _settingsState.update { it.copy(httpPort = InputFieldState(value, error, error == null)) }
        return error == null
    }

    fun setSocksUdpEnabled(enabled: Boolean) {
        prefs.socksUdpEnabled = enabled
        _settingsState.update { it.copy(switches = it.switches.copy(socksUdpEnabled = enabled)) }
    }

    fun setAllowLanAccess(enabled: Boolean) {
        if (!enabled) prefs.socksAddress = "127.0.0.1"
        prefs.allowLanAccess = enabled
        _settingsState.update {
            it.copy(
                socksAddress = InputFieldState(prefs.socksAddress),
                switches = it.switches.copy(allowLanAccess = enabled),
            )
        }
    }

    fun setIpv6Enabled(enabled: Boolean) {
        prefs.ipv6 = enabled
        _settingsState.value =
            _settingsState.value.copy(
                switches = _settingsState.value.switches.copy(ipv6Enabled = enabled)
            )
    }

    fun setHttpProxyEnabled(enabled: Boolean) {
        if (enabled && prefs.httpPort == prefs.socksPort) {
            _settingsState.update {
                it.copy(
                    httpPort =
                        InputFieldState(
                            prefs.httpPort.toString(),
                            "Порты SOCKS и HTTP должны отличаться",
                            false,
                        )
                )
            }
            return
        }
        prefs.httpProxyEnabled = enabled
        _settingsState.value =
            _settingsState.value.copy(
                switches = _settingsState.value.switches.copy(httpProxyEnabled = enabled)
            )
    }

    fun setBypassLanEnabled(enabled: Boolean) {
        prefs.bypassLan = enabled
        _settingsState.value =
            _settingsState.value.copy(
                switches = _settingsState.value.switches.copy(bypassLanEnabled = enabled)
            )
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.autoStartEnabled = enabled
        _settingsState.update { it.copy(switches = it.switches.copy(autoStartEnabled = enabled)) }
    }

    fun setDisableVpnEnabled(enabled: Boolean) {
        prefs.disableVpn = enabled
        _settingsState.value =
            _settingsState.value.copy(
                switches = _settingsState.value.switches.copy(disableVpn = enabled)
            )
    }

    fun setTheme(mode: ThemeMode) {
        prefs.theme = mode
        _settingsState.value =
            _settingsState.value.copy(
                switches = _settingsState.value.switches.copy(themeMode = mode)
            )
        reloadView()
    }

    fun updateConnectivityTestTarget(target: String) {
        val isValid =
            try {
                val url = URL(target)
                url.protocol in listOf("http", "https") &&
                    url.host.isNotBlank() &&
                    url.userInfo == null
            } catch (e: Exception) {
                false
            }
        if (isValid) {
            if (prefs.connectivityTestTarget != target) resetServerChecks()
            prefs.connectivityTestTarget = target
            _settingsState.value =
                _settingsState.value.copy(connectivityTestTarget = InputFieldState(target))
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    connectivityTestTarget =
                        InputFieldState(
                            value = target,
                            error = application.getString(R.string.connectivity_test_invalid_url),
                            isValid = false,
                        )
                )
        }
    }

    fun updateConnectivityTestTimeout(timeout: String) {
        val timeoutInt = timeout.toIntOrNull()
        if (timeoutInt != null && timeoutInt in 1..30000) {
            if (prefs.connectivityTestTimeout != timeoutInt) resetServerChecks()
            prefs.connectivityTestTimeout = timeoutInt
            _settingsState.value =
                _settingsState.value.copy(connectivityTestTimeout = InputFieldState(timeout))
        } else {
            _settingsState.value =
                _settingsState.value.copy(
                    connectivityTestTimeout =
                        InputFieldState(
                            value = timeout,
                            error = "Укажи от 1 до 30 000 мс",
                            isValid = false,
                        )
                )
        }
    }

    fun updateProbeMethod(method: ProbeMethod) {
        if (selectedProbeMethod() != method) resetServerChecks()
        prefs.probeMethod = method.name
        _settingsState.update { it.copy(probeMethod = method) }
    }

    private fun selectedProbeMethod() =
        runCatching { ProbeMethod.valueOf(prefs.probeMethod) }.getOrDefault(ProbeMethod.HTTP_GET)

    fun refreshRuleFileState(fileName: String) {
        when (fileName) {
            "geoip.dat" -> {
                _settingsState.value =
                    _settingsState.value.copy(
                        files =
                            _settingsState.value.files.copy(
                                isGeoipCustom = prefs.customGeoipImported
                            ),
                        info =
                            _settingsState.value.info.copy(
                                geoipSummary = fileManager.getRuleFileSummary("geoip.dat")
                            ),
                    )
            }

            "geosite.dat" -> {
                _settingsState.value =
                    _settingsState.value.copy(
                        files =
                            _settingsState.value.files.copy(
                                isGeositeCustom = prefs.customGeositeImported
                            ),
                        info =
                            _settingsState.value.info.copy(
                                geositeSummary = fileManager.getRuleFileSummary("geosite.dat")
                            ),
                    )
            }
        }
    }

    companion object {
        private const val IPV4_REGEX =
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        private val IPV4_PATTERN: Pattern = Pattern.compile(IPV4_REGEX)
        private const val IPV6_REGEX =
            "^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80::(fe80(:[0-9a-fA-F]{0,4})?){0,4}%[0-9a-zA-Z]+|::(ffff(:0{1,4})?:)?((25[0-5]|(2[0-4]|1?\\d)?\\d)\\.){3}(25[0-5]|(2[0-4]|1?\\d)?\\d)|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1?\\d)?\\d)\\.){3}(25[0-5]|(2[0-4]|1?\\d)?\\d))$"
        private val IPV6_PATTERN: Pattern = Pattern.compile(IPV6_REGEX)
    }
}
