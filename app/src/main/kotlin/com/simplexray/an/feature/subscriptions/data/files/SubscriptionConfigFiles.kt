package com.simplexray.an.feature.subscriptions.data.files

import android.app.Application
import android.util.Log
import com.simplexray.an.core.config.ConfigUtils
import com.simplexray.an.feature.servers.importing.DetectedConfig
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.prefs.Preferences
import java.io.File
import java.io.IOException

internal class SubscriptionConfigFiles(
    private val application: Application,
    private val prefs: Preferences,
) {
    fun write(sub: Subscription, servers: List<DetectedConfig>, filesDir: File): List<String> {
        val newFileNames = mutableListOf<String>()
        val ownFiles = sub.files.toSet()
        val usedNames =
            (filesDir
                    .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                    ?.map { it.name }
                    ?.filter { it !in ownFiles } ?: emptyList())
                .toMutableSet()

        for ((serverName, configJson) in servers) {
            val fileName = uniqueFileName(sub.name, serverName, usedNames)
            usedNames.add(fileName)
            val formatted =
                try {
                    ConfigUtils.formatConfigContent(configJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Skipping malformed server config: $serverName", e)
                    continue
                }
            try {
                File(filesDir, fileName).writeText(formatted)
                newFileNames.add(fileName)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write $fileName", e)
            }
        }

        return newFileNames
    }

    private fun uniqueFileName(subName: String, serverName: String, used: Set<String>): String {
        val safeSub = sanitize(subName)
        val safeServer = sanitize(serverName)
        val base = "$safeSub - $safeServer"
        var candidate = "$base.json"
        var i = 2
        while (candidate in used) {
            candidate = "$base ($i).json"
            i++
        }
        return candidate
    }

    fun reconcileOrderAndSelection(newFiles: List<String>) {
        val filesDir = application.filesDir
        val actual =
            filesDir
                .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.map { it.name }
                ?.toMutableSet() ?: mutableSetOf()

        val order = prefs.configFilesOrder.toMutableList()
        order.removeAll { it !in actual }
        newFiles.forEach { if (it !in order && it in actual) order.add(it) }
        prefs.configFilesOrder = order

        val selected = prefs.selectedConfigPath
        if (selected != null) {
            val selectedName = File(selected).name
            if (selectedName !in actual) {
                prefs.selectedConfigPath = null
            }
        }
    }

    companion object {
        private const val TAG = "SubscriptionManager"

        internal fun serverName(subscription: Subscription, filename: String): String =
            filename.removePrefix("${sanitize(subscription.name)} - ").ifBlank { "Сервер" }

        private fun sanitize(name: String): String {
            val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            return cleaned.ifEmpty { "server" }
        }
    }
}
