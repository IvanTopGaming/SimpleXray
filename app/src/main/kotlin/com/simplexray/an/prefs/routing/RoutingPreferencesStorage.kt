package com.simplexray.an.prefs.routing

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.google.gson.JsonObject
import com.simplexray.an.R
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.prefs.PrefsContract
import java.io.File

internal class RoutingPreferencesStorage(
    private val context: Context?,
    private val prefs: SharedPreferences,
) {
    fun call(method: String, extras: Bundle?): Bundle {
        val snapshot = prefs.all
        val geoipUrl =
            snapshot[Preferences.GEOIP_URL] as? String
                ?: requireNotNull(context).getString(R.string.geoip_url)
        val geositeUrl =
            snapshot[Preferences.GEOSITE_URL] as? String
                ?: requireNotNull(context).getString(R.string.geosite_url)
        when (method) {
            PrefsContract.READ_PENDING_GEODATA ->
                return Bundle().apply {
                    (snapshot[PrefsContract.PENDING_GEOIP] as? String)?.let {
                        putString("geoip.dat", it)
                    }
                    (snapshot[PrefsContract.PENDING_GEOSITE] as? String)?.let {
                        putString("geosite.dat", it)
                    }
                }
            PrefsContract.APPLY_ROUTING_PRESET -> {
                val values = requireNotNull(extras)
                val routingJson = requireNotNull(values.getString(PrefsContract.ROUTING_SETTINGS))
                val preset =
                    RoutingPreset(
                        RoutingSettings.decode(routingJson),
                        requireNotNull(values.getString(Preferences.GEOIP_URL)),
                        requireNotNull(values.getString(Preferences.GEOSITE_URL)),
                    )
                preset.validate()
                prefs
                    .edit()
                    .apply {
                        putString(PrefsContract.ROUTING_SETTINGS, routingJson)
                        putString(Preferences.GEOIP_URL, preset.geoipUrl)
                        putString(Preferences.GEOSITE_URL, preset.geositeUrl)
                        if (geoipUrl != preset.geoipUrl)
                            putString(PrefsContract.PENDING_GEOIP, preset.geoipUrl)
                        if (geositeUrl != preset.geositeUrl)
                            putString(PrefsContract.PENDING_GEOSITE, preset.geositeUrl)
                    }
                    .apply()
            }
            PrefsContract.ACKNOWLEDGE_GEODATA,
            PrefsContract.PUBLISH_GEODATA_SOURCE -> {
                val values = requireNotNull(extras)
                val fileName = requireNotNull(values.getString(PrefsContract.FILE_NAME))
                val url = requireNotNull(values.getString(PrefsContract.SOURCE_URL))
                val (sourceKey, pendingKey) =
                    when (fileName) {
                        "geoip.dat" -> Preferences.GEOIP_URL to PrefsContract.PENDING_GEOIP
                        "geosite.dat" -> Preferences.GEOSITE_URL to PrefsContract.PENDING_GEOSITE
                        else -> throw IllegalArgumentException("Unknown geodata file")
                    }
                if (method == PrefsContract.PUBLISH_GEODATA_SOURCE) {
                    prefs.edit().putString(sourceKey, url).remove(pendingKey).apply()
                } else {
                    val currentUrl = if (fileName == "geoip.dat") geoipUrl else geositeUrl
                    if (currentUrl == url && snapshot[pendingKey] == url) {
                        prefs.edit().remove(pendingKey).apply()
                    }
                }
            }
            else -> throw UnsupportedOperationException("Unknown preferences operation: $method")
        }
        context?.contentResolver?.notifyChange(PrefsContract.PrefsEntry.CONTENT_URI, null)
        return Bundle()
    }

    fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(uri == PrefsContract.ROUTING_PRESET_SNAPSHOT_URI && mode == "r") {
            "Unsupported routing snapshot request"
        }
        val values = prefs.all
        val snapshot =
            JsonObject().apply {
                addProperty(
                    PrefsContract.ROUTING_SETTINGS,
                    values[PrefsContract.ROUTING_SETTINGS] as? String,
                )
                addProperty(
                    Preferences.GEOIP_URL,
                    values[Preferences.GEOIP_URL] as? String
                        ?: requireNotNull(context).getString(R.string.geoip_url),
                )
                addProperty(
                    Preferences.GEOSITE_URL,
                    values[Preferences.GEOSITE_URL] as? String
                        ?: requireNotNull(context).getString(R.string.geosite_url),
                )
                addProperty(
                    PrefsContract.PENDING_GEOIP,
                    values[PrefsContract.PENDING_GEOIP] as? String,
                )
                addProperty(
                    PrefsContract.PENDING_GEOSITE,
                    values[PrefsContract.PENDING_GEOSITE] as? String,
                )
            }
        val temporary =
            File.createTempFile(".routing-preset-", ".json", requireNotNull(context).cacheDir)
        var descriptor: ParcelFileDescriptor? = null
        try {
            temporary.writeText(snapshot.toString())
            descriptor = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
            check(temporary.delete()) { "Unable to unlink routing snapshot" }
            return descriptor
        } catch (error: Throwable) {
            runCatching { descriptor?.close() }
            throw error
        } finally {
            temporary.delete()
        }
    }
}
