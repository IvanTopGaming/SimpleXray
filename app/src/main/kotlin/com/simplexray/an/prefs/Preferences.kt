package com.simplexray.an.prefs

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.simplexray.an.R
import com.simplexray.an.feature.apps.model.AppRoutingMode
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.settings.model.InboundSettings
import com.simplexray.an.feature.subscriptions.data.subscriptionInstallationId
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.feature.subscriptions.model.SubscriptionUpdateInterval
import com.simplexray.an.prefs.routing.RoutingPreferencesClient
import com.simplexray.an.prefs.transport.PreferenceProviderClient
import com.simplexray.an.ui.theme.ThemeMode

class Preferences(context: Context) {
    private val contentResolver: ContentResolver
    private val gson: Gson
    private val context1: Context = context.applicationContext

    init {
        this.contentResolver = context1.contentResolver
        this.gson = Gson()
    }

    private val provider = PreferenceProviderClient(contentResolver)
    private val routing = RoutingPreferencesClient(contentResolver)

    fun readRoutingPreset(): RoutingPreset = routing.readRoutingPreset()

    fun readRoutingPresetState(): Pair<RoutingPreset, Map<String, String>> =
        routing.readRoutingPresetState()

    fun applyRoutingPreset(preset: RoutingPreset) = routing.applyRoutingPreset(preset)

    val pendingGeodata: Map<String, String>
        get() = routing.pendingGeodata

    fun acknowledgeGeodata(fileName: String, url: String) =
        routing.acknowledgeGeodata(fileName, url)

    var routingSettingsJson: String?
        get() = provider.read("RoutingSettings").first
        set(value) {
            provider.write("RoutingSettings", value)
        }

    var dnsSettingsJson: String?
        get() = provider.read(DNS_SETTINGS).first
        set(value) {
            provider.write(DNS_SETTINGS, value)
        }

    var kernelSettingsJson: String?
        get() = provider.read(KERNEL_SETTINGS).first
        set(value) {
            provider.write(KERNEL_SETTINGS, value)
        }

    var logSettingsJson: String?
        get() = provider.read(LOG_SETTINGS).first
        set(value) {
            provider.write(LOG_SETTINGS, value)
        }

    var profileOverridesJson: String?
        get() = provider.read(PROFILE_OVERRIDES).first
        set(value) {
            provider.write(PROFILE_OVERRIDES, value)
        }

    var activeProxySettingsJson: String?
        get() = provider.read(ACTIVE_PROXY_SETTINGS).first
        set(value) {
            provider.write(ACTIVE_PROXY_SETTINGS, value)
        }

    var activeTrafficStatsEnabled: Boolean
        get() = provider.boolean(ACTIVE_TRAFFIC_STATS_ENABLED, true)
        set(value) {
            provider.write(ACTIVE_TRAFFIC_STATS_ENABLED, value)
        }

    var httpPort: Int
        get() {
            val stored = provider.read(HTTP_PORT).first
            return stored?.toIntOrNull()
                ?: if (stored == null && socksPort == 10809) 10810 else 10809
        }
        set(value) {
            require(value in 1..65535)
            provider.write(HTTP_PORT, value)
        }

    var socksUdpEnabled: Boolean
        get() = provider.boolean(SOCKS_UDP_ENABLED, true)
        set(value) {
            provider.write(SOCKS_UDP_ENABLED, value)
        }

    var allowLanAccess: Boolean
        get() = provider.boolean(ALLOW_LAN_ACCESS, !InboundSettings.isLoopback(socksAddress))
        set(value) {
            provider.write(ALLOW_LAN_ACCESS, value)
        }

    var appRoutingMode: AppRoutingMode
        get() {
            val stored = provider.read(APP_ROUTING_MODE).first
            return AppRoutingMode.entries.firstOrNull { it.name == stored }
                ?: when {
                    apps.isNullOrEmpty() -> AppRoutingMode.ALL
                    bypassSelectedApps -> AppRoutingMode.EXCLUDE
                    else -> AppRoutingMode.INCLUDE
                }
        }
        set(value) {
            provider.write(APP_ROUTING_MODE, value.name)
            when (value) {
                AppRoutingMode.EXCLUDE -> bypassSelectedApps = true
                AppRoutingMode.INCLUDE -> bypassSelectedApps = false
                AppRoutingMode.ALL -> Unit
            }
        }

    var socksAddress: String
        get() = provider.read(SOCKS_ADDR).first ?: "127.0.0.1"
        set(address) {
            provider.write(SOCKS_ADDR, address)
        }

    var socksPort: Int
        get() {
            val value = provider.read(SOCKS_PORT).first
            val port = value?.toIntOrNull()
            if (value != null && port == null) {
                Log.e(TAG, "Failed to parse SocksPort as Integer: $value")
            }
            return port ?: 10808
        }
        set(port) {
            provider.write(SOCKS_PORT, port.toString())
        }

    var socksUsername: String
        get() = provider.read(SOCKS_USER).first ?: ""
        set(user) {
            provider.write(SOCKS_USER, user)
        }

    var socksPassword: String
        get() = provider.read(SOCKS_PASS).first ?: ""
        set(pass) {
            provider.write(SOCKS_PASS, pass)
        }

    var dnsIpv4: String
        get() = provider.read(DNS_IPV4).first ?: "8.8.8.8"
        set(addr) {
            provider.write(DNS_IPV4, addr)
        }

    var dnsIpv6: String
        get() = provider.read(DNS_IPV6).first ?: "2001:4860:4860::8888"
        set(addr) {
            provider.write(DNS_IPV6, addr)
        }

    val udpInTcp: Boolean
        get() = provider.boolean(UDP_IN_TCP, false)

    var ipv4: Boolean
        get() = provider.boolean(IPV4, true)
        set(enable) {
            provider.write(IPV4, enable)
        }

    var ipv6: Boolean
        get() = provider.boolean(IPV6, false)
        set(enable) {
            provider.write(IPV6, enable)
        }

    var global: Boolean
        get() = provider.boolean(GLOBAL, false)
        set(enable) {
            provider.write(GLOBAL, enable)
        }

    var apps: Set<String?>?
        get() {
            val jsonSet = provider.read(APPS).first
            return jsonSet?.let {
                try {
                    val type = object : TypeToken<Set<String?>?>() {}.type
                    gson.fromJson<Set<String?>>(it, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing APPS StringSet", e)
                    null
                }
            }
        }
        set(apps) {
            val jsonSet = gson.toJson(apps)
            provider.write(APPS, jsonSet)
        }

    var enable: Boolean
        get() = provider.boolean(ENABLE, false)
        set(enable) {
            provider.write(ENABLE, enable)
        }

    var autoStartEnabled: Boolean
        get() = provider.boolean(AUTO_START_ENABLED, false)
        set(value) {
            provider.write(AUTO_START_ENABLED, value)
        }

    var disableVpn: Boolean
        get() = provider.boolean(DISABLE_VPN, false)
        set(value) {
            provider.write(DISABLE_VPN, value)
        }

    val tunnelIpv4Address: String
        get() = "198.18.0.1"

    val tunnelIpv4Prefix: Int
        get() = 32

    val tunnelIpv6Address: String
        get() = "fc00::1"

    val tunnelIpv6Prefix: Int
        get() = 128

    val taskStackSize: Int
        get() = 81920

    var selectedConfigPath: String?
        get() = provider.read(SELECTED_CONFIG_PATH).first
        set(path) {
            provider.write(SELECTED_CONFIG_PATH, path)
        }

    var bypassLan: Boolean
        get() = provider.boolean(BYPASS_LAN, true)
        set(enable) {
            provider.write(BYPASS_LAN, enable)
        }

    var httpProxyEnabled: Boolean
        get() = provider.boolean(HTTP_PROXY_ENABLED, true)
        set(enable) {
            provider.write(HTTP_PROXY_ENABLED, enable)
        }

    var customGeoipImported: Boolean
        get() = provider.boolean(CUSTOM_GEOIP_IMPORTED, false)
        set(imported) {
            provider.write(CUSTOM_GEOIP_IMPORTED, imported)
        }

    var customGeositeImported: Boolean
        get() = provider.boolean(CUSTOM_GEOSITE_IMPORTED, false)
        set(imported) {
            provider.write(CUSTOM_GEOSITE_IMPORTED, imported)
        }

    var configFilesOrder: List<String>
        get() {
            val jsonList = provider.read(CONFIG_FILES_ORDER).first
            return jsonList?.let {
                try {
                    val type = object : TypeToken<List<String>>() {}.type
                    gson.fromJson(it, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing CONFIG_FILES_ORDER List<String>", e)
                    emptyList()
                }
            } ?: emptyList()
        }
        set(order) {
            val jsonList = gson.toJson(order)
            provider.write(CONFIG_FILES_ORDER, jsonList)
        }

    var autoUpdateSubscriptions: Boolean
        get() = provider.boolean("AutoUpdateSubscriptions", true)
        set(value) {
            provider.write("AutoUpdateSubscriptions", value)
        }

    var subscriptionSendHwid: Boolean
        get() = provider.boolean(SUBSCRIPTION_SEND_HWID, false)
        set(value) {
            provider.write(SUBSCRIPTION_SEND_HWID, value)
        }

    var subscriptionClientIdentityJson: String
        get() = provider.read(SUBSCRIPTION_CLIENT_IDENTITY).first ?: "{}"
        set(value) {
            provider.write(SUBSCRIPTION_CLIENT_IDENTITY, value)
        }

    var subscriptionUpdateIntervalMinutes: Int
        get() =
            SubscriptionUpdateInterval.fromMinutes(
                    provider.read(SUBSCRIPTION_UPDATE_INTERVAL).first?.toIntOrNull()
                )
                .minutes
        set(value) {
            provider.write(
                SUBSCRIPTION_UPDATE_INTERVAL,
                SubscriptionUpdateInterval.fromMinutes(value).minutes,
            )
        }

    val subscriptionInstallationId: String
        get() = subscriptionInstallationId(context1.noBackupFilesDir)

    var probeMethod: String
        get() = provider.read(PROBE_METHOD).first ?: "HTTP_GET"
        set(value) {
            provider.write(PROBE_METHOD, value)
        }

    var subscriptions: List<Subscription>
        get() {
            val jsonList = provider.read(SUBSCRIPTIONS).first
            return jsonList?.let {
                try {
                    val type = object : TypeToken<List<Subscription>>() {}.type
                    gson.fromJson(it, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing SUBSCRIPTIONS List<Subscription>", e)
                    emptyList()
                }
            } ?: emptyList()
        }
        set(value) {
            provider.write(SUBSCRIPTIONS, gson.toJson(value))
        }

    var connectivityTestTarget: String
        get() =
            provider.read(CONNECTIVITY_TEST_TARGET).first
                ?: context1.getString(R.string.connectivity_test_url)
        set(value) {
            provider.write(CONNECTIVITY_TEST_TARGET, value)
        }

    var connectivityTestTimeout: Int
        get() = provider.read(CONNECTIVITY_TEST_TIMEOUT).first?.toIntOrNull() ?: 3000
        set(value) {
            provider.write(CONNECTIVITY_TEST_TIMEOUT, value.toString())
        }

    var geoipUrl: String
        get() = provider.read(GEOIP_URL).first ?: context1.getString(R.string.geoip_url)
        set(value) {
            routing.geodataOperation(PrefsContract.PUBLISH_GEODATA_SOURCE, "geoip.dat", value)
        }

    var geositeUrl: String
        get() = provider.read(GEOSITE_URL).first ?: context1.getString(R.string.geosite_url)
        set(value) {
            routing.geodataOperation(PrefsContract.PUBLISH_GEODATA_SOURCE, "geosite.dat", value)
        }

    var apiAddress: String
        get() = provider.read(API_ADDRESS).first ?: "127.0.0.1"
        set(address) {
            provider.write(API_ADDRESS, address)
        }

    var apiPort: Int
        get() {
            val value = provider.read(API_PORT).first
            val port = value?.toIntOrNull()
            return port ?: 0
        }
        set(port) {
            provider.write(API_PORT, port.toString())
        }

    var bypassSelectedApps: Boolean
        get() = provider.boolean(BYPASS_SELECTED_APPS, false)
        set(enable) {
            provider.write(BYPASS_SELECTED_APPS, enable)
        }

    var theme: ThemeMode
        get() = provider.read(THEME).first?.let { ThemeMode.fromString(it) } ?: ThemeMode.Auto
        set(value) {
            provider.write(THEME, value.value)
        }

    companion object {
        const val LOG_SETTINGS: String = "LogSettings"
        const val PROFILE_OVERRIDES: String = "ProfileOverrides"
        const val ACTIVE_PROXY_SETTINGS: String = "ActiveProxySettings"
        const val ACTIVE_TRAFFIC_STATS_ENABLED: String = "ActiveTrafficStatsEnabled"
        const val HTTP_PORT: String = "HttpPort"
        const val SOCKS_UDP_ENABLED: String = "SocksUdpEnabled"
        const val ALLOW_LAN_ACCESS: String = "AllowLanAccess"
        const val APP_ROUTING_MODE: String = "AppRoutingMode"
        const val SOCKS_ADDR: String = "SocksAddr"
        const val SOCKS_PORT: String = "SocksPort"
        const val SOCKS_USER: String = "SocksUser"
        const val SOCKS_PASS: String = "SocksPass"
        const val KERNEL_SETTINGS: String = "KernelSettings"
        const val DNS_SETTINGS: String = "DnsSettings"
        const val DNS_IPV4: String = "DnsIpv4"
        const val DNS_IPV6: String = "DnsIpv6"
        const val IPV4: String = "Ipv4"
        const val IPV6: String = "Ipv6"
        const val GLOBAL: String = "Global"
        const val UDP_IN_TCP: String = "UdpInTcp"
        const val APPS: String = "Apps"
        const val ENABLE: String = "Enable"
        const val SELECTED_CONFIG_PATH: String = "SelectedConfigPath"
        const val BYPASS_LAN: String = "BypassLan"
        const val HTTP_PROXY_ENABLED: String = "HttpProxyEnabled"
        const val CUSTOM_GEOIP_IMPORTED: String = "CustomGeoipImported"
        const val CUSTOM_GEOSITE_IMPORTED: String = "CustomGeositeImported"
        const val CONFIG_FILES_ORDER: String = "ConfigFilesOrder"
        const val SUBSCRIPTIONS: String = "Subscriptions"
        const val SUBSCRIPTION_SEND_HWID: String = "SubscriptionSendHwid"
        const val SUBSCRIPTION_CLIENT_IDENTITY: String = "SubscriptionClientIdentity"
        const val SUBSCRIPTION_UPDATE_INTERVAL: String = "SubscriptionUpdateInterval"
        const val PROBE_METHOD: String = "ProbeMethod"
        const val AUTO_START_ENABLED: String = "AutoStartEnabled"
        const val DISABLE_VPN: String = "DisableVpn"
        const val CONNECTIVITY_TEST_TARGET: String = "ConnectivityTestTarget"
        const val CONNECTIVITY_TEST_TIMEOUT: String = "ConnectivityTestTimeout"
        const val GEOIP_URL: String = "GeoipUrl"
        const val GEOSITE_URL: String = "GeositeUrl"
        const val API_ADDRESS: String = "ApiAddress"
        const val API_PORT: String = "ApiPort"
        const val BYPASS_SELECTED_APPS: String = "BypassSelectedApps"
        const val THEME: String = "Theme"
        private const val TAG = "Preferences"
    }
}
