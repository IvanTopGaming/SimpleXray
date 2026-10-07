package com.simplexray.an.prefs.routing

import android.content.ContentResolver
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.google.gson.JsonParser
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.prefs.Preferences.Companion.GEOIP_URL
import com.simplexray.an.prefs.Preferences.Companion.GEOSITE_URL
import com.simplexray.an.prefs.PrefsContract

internal class RoutingPreferencesClient(private val contentResolver: ContentResolver) {
    fun readRoutingPreset(): RoutingPreset = readRoutingPresetState().first

    fun readRoutingPresetState(): Pair<RoutingPreset, Map<String, String>> {
        val descriptor =
            requireNotNull(
                contentResolver.openFileDescriptor(PrefsContract.ROUTING_PRESET_SNAPSHOT_URI, "r")
            )
        val snapshot =
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                JsonParser.parseString(RoutingPreset.readText(input)).asJsonObject
            }
        fun string(key: String): String? = snapshot[key]?.takeUnless { it.isJsonNull }?.asString
        val preset =
            RoutingPreset(
                RoutingSettings.decode(string(PrefsContract.ROUTING_SETTINGS)),
                requireNotNull(string(GEOIP_URL)),
                requireNotNull(string(GEOSITE_URL)),
            )
        val pending = buildMap {
            string(PrefsContract.PENDING_GEOIP)?.let { put("geoip.dat", it) }
            string(PrefsContract.PENDING_GEOSITE)?.let { put("geosite.dat", it) }
        }
        return preset to pending
    }

    fun applyRoutingPreset(preset: RoutingPreset) {
        preset.validate()
        val values =
            Bundle().apply {
                putString(PrefsContract.ROUTING_SETTINGS, preset.routing.encode())
                putString(GEOIP_URL, preset.geoipUrl)
                putString(GEOSITE_URL, preset.geositeUrl)
            }
        callProvider(PrefsContract.APPLY_ROUTING_PRESET, values)
    }

    val pendingGeodata: Map<String, String>
        get() {
            val snapshot = callProvider(PrefsContract.READ_PENDING_GEODATA)
            return snapshot.keySet().associateWith { requireNotNull(snapshot.getString(it)) }
        }

    fun acknowledgeGeodata(fileName: String, url: String) {
        geodataOperation(PrefsContract.ACKNOWLEDGE_GEODATA, fileName, url)
    }

    fun geodataOperation(method: String, fileName: String, url: String) {
        require(fileName == "geoip.dat" || fileName == "geosite.dat") { "Unknown geodata file" }
        callProvider(
            method,
            Bundle().apply {
                putString(PrefsContract.FILE_NAME, fileName)
                putString(PrefsContract.SOURCE_URL, url)
            },
        )
    }

    private fun callProvider(method: String, values: Bundle? = null): Bundle =
        checkNotNull(
            contentResolver.call(PrefsContract.PrefsEntry.CONTENT_URI, method, null, values)
        ) {
            "Preferences provider unavailable"
        }
}
