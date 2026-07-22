package com.simplexray.an.data.source

import android.app.Application
import android.util.Log
import com.simplexray.an.R
import com.simplexray.an.common.ConfigUtils
import com.simplexray.an.common.configFormat.SubscriptionParser
import com.simplexray.an.data.model.Subscription
import com.simplexray.an.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

class SubscriptionManager(
    private val application: Application,
    private val prefs: Preferences,
    private val isServiceEnabled: () -> Boolean
) {
    suspend fun add(name: String, url: String): Result<Subscription> {
        val sub = Subscription(
            id = System.currentTimeMillis().toString(),
            name = name.trim(),
            url = url.trim(),
            lastUpdated = 0L,
            files = emptyList()
        )
        prefs.subscriptions = prefs.subscriptions + sub
        return refresh(sub.id)
    }

    suspend fun refresh(id: String): Result<Subscription> = withContext(Dispatchers.IO) {
        val sub = prefs.subscriptions.find { it.id == id }
            ?: return@withContext Result.failure(
                IOException(application.getString(R.string.subscription_error_not_found))
            )

        val body = try {
            fetch(sub.url)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed for ${sub.url}", e)
            return@withContext Result.failure(
                IOException(application.getString(R.string.subscription_error_network))
            )
        }

        val servers = SubscriptionParser.parse(application, body)
        if (servers.isEmpty()) {
            return@withContext Result.failure(
                IOException(application.getString(R.string.subscription_error_empty))
            )
        }

        val filesDir = application.filesDir
        val newFileNames = mutableListOf<String>()
        val ownFiles = sub.files.toSet()
        val usedNames = (filesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { it.name }?.filter { it !in ownFiles } ?: emptyList()).toMutableSet()

        for ((serverName, configJson) in servers) {
            val fileName = uniqueFileName(sub.name, serverName, usedNames)
            usedNames.add(fileName)
            val formatted = try {
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

        if (newFileNames.isEmpty()) {
            return@withContext Result.failure(
                IOException(application.getString(R.string.subscription_error_empty))
            )
        }

        val removedPaths = sub.files
            .filter { it !in newFileNames }
            .map { File(filesDir, it) }
        removedPaths.forEach { if (it.exists()) it.delete() }

        val updated = sub.copy(files = newFileNames, lastUpdated = System.currentTimeMillis())
        prefs.subscriptions = prefs.subscriptions.map { if (it.id == id) updated else it }

        reconcileOrderAndSelection(newFileNames)

        Result.success(updated)
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val sub = prefs.subscriptions.find { it.id == id } ?: return@withContext false
        val filesDir = application.filesDir
        sub.files.forEach { name ->
            val f = File(filesDir, name)
            if (f.exists()) f.delete()
        }
        prefs.subscriptions = prefs.subscriptions.filter { it.id != id }
        reconcileOrderAndSelection(emptyList())
        true
    }

    private fun fetch(url: String): String {
        val client = OkHttpClient.Builder().apply {
            if (isServiceEnabled()) {
                proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", prefs.socksPort)))
            }
        }.build()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("Empty body")
        }
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

    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifEmpty { "server" }
    }

    private fun reconcileOrderAndSelection(newFiles: List<String>) {
        val filesDir = application.filesDir
        val actual = filesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { it.name }?.toMutableSet() ?: mutableSetOf()

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
        const val TAG = "SubscriptionManager"
    }
}
