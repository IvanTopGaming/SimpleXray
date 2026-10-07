package com.simplexray.an.feature.subscriptions.data

import android.app.Application
import android.util.Log
import com.simplexray.an.R
import com.simplexray.an.core.files.config.ServerConfigLock
import com.simplexray.an.feature.servers.importing.SubscriptionParser
import com.simplexray.an.feature.subscriptions.data.files.SubscriptionConfigFiles
import com.simplexray.an.feature.subscriptions.data.remote.SubscriptionDocumentClient
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.prefs.Preferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SubscriptionManager(
    private val application: Application,
    private val prefs: Preferences,
    isServiceEnabled: () -> Boolean,
) {
    private val documentClient = SubscriptionDocumentClient(prefs, isServiceEnabled)
    private val configFiles = SubscriptionConfigFiles(application, prefs)

    suspend fun create(name: String, url: String): Subscription =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sub =
                    Subscription(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name.trim(),
                        url = url.trim(),
                        lastUpdated = 0L,
                        files = emptyList(),
                    )
                prefs.subscriptions = prefs.subscriptions + sub
                sub
            }
        }

    suspend fun refreshAll(): Boolean =
        withContext(Dispatchers.IO) {
            var retry = false
            val ids = prefs.subscriptions.map { it.id }
            for (id in ids) {
                currentCoroutineContext().ensureActive()
                configMutex.withLock {
                    if (!prefs.autoUpdateSubscriptions) return@withContext false
                    if (prefs.subscriptions.any { it.id == id } && refresh(id).isFailure)
                        retry = true
                }
            }
            retry
        }

    suspend fun refresh(id: String, prefetched: Result<Response>? = null): Result<Subscription> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sub =
                    prefs.subscriptions.find { it.id == id }
                        ?: return@withLock Result.failure(
                            IOException(
                                application.getString(R.string.subscription_error_not_found)
                            )
                        )

                val response =
                    try {
                        prefetched?.getOrThrow() ?: fetch(sub.url)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SubscriptionClientIdentityException) {
                        return@withLock failed(sub, e.message.orEmpty())
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        Log.e(TAG, "Subscription fetch failed: ${e.javaClass.simpleName}")
                        return@withLock failed(sub, R.string.subscription_error_network)
                    }
                currentCoroutineContext().ensureActive()

                val servers =
                    try {
                        SubscriptionParser.parse(application, response.body)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        Log.e(TAG, "Subscription parse failed: ${e.javaClass.simpleName}")
                        return@withLock failed(sub, R.string.invalid_config_format)
                    }
                if (servers.isEmpty()) {
                    return@withLock failed(sub, R.string.subscription_error_empty)
                }

                val filesDir = application.filesDir
                currentCoroutineContext().ensureActive()
                ServerConfigLock.withSnapshot(filesDir) publish@{
                    val newFileNames = configFiles.write(sub, servers, filesDir)

                    if (newFileNames.isEmpty()) {
                        return@publish failed(sub, R.string.subscription_error_empty)
                    }

                    val removedPaths =
                        sub.files.filter { it !in newFileNames }.map { File(filesDir, it) }
                    removedPaths.forEach { if (it.exists()) it.delete() }

                    val updated =
                        sub.copy(
                            files = newFileNames,
                            lastUpdated = System.currentTimeMillis(),
                            displayTitle = response.title,
                            usage = parseSubscriptionUsage(response.userinfo),
                            lastError = null,
                        )
                    prefs.subscriptions =
                        prefs.subscriptions.map { if (it.id == id) updated else it }

                    configFiles.reconcileOrderAndSelection(newFileNames)

                    Result.success(updated)
                }
            }
        }

    suspend fun delete(id: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sub = prefs.subscriptions.find { it.id == id } ?: return@withLock false
                val filesDir = application.filesDir
                ServerConfigLock.withSnapshot(filesDir) {
                    sub.files.forEach { name ->
                        val f = File(filesDir, name)
                        if (f.exists()) f.delete()
                    }
                    prefs.subscriptions = prefs.subscriptions.filter { it.id != id }
                    configFiles.reconcileOrderAndSelection(emptyList())
                    true
                }
            }
        }

    suspend fun updateUrl(id: String, url: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (url.toHttpUrlOrNull() == null || prefs.subscriptions.none { it.id == id })
                    return@withLock false
                prefs.subscriptions =
                    prefs.subscriptions.map {
                        if (it.id == id && it.url != url.trim())
                            it.copy(
                                url = url.trim(),
                                usage = null,
                                lastError = null,
                                displayTitle = subscriptionTitle(url.trim(), null, null),
                                lastUpdated = 0,
                            )
                        else it
                    }
                true
            }
        }

    data class Response(val body: String, val title: String, val userinfo: String?)

    suspend fun fetchForImport(url: String, subscriptionIdentity: Boolean = true): Response =
        withContext(Dispatchers.IO) {
            val response = documentClient.fetchDocument(url, subscriptionIdentity, true)
            currentCoroutineContext().ensureActive()
            response
        }

    private fun failed(sub: Subscription, messageId: Int): Result<Subscription> =
        failed(sub, application.getString(messageId))

    private fun failed(sub: Subscription, message: String): Result<Subscription> {
        prefs.subscriptions =
            prefs.subscriptions.map { if (it.id == sub.id) it.copy(lastError = message) else it }
        return Result.failure(IOException(message))
    }

    private fun fetch(url: String): Response = documentClient.fetchDocument(url, true, false)

    companion object {
        const val TAG = "SubscriptionManager"
        private val mutex = Mutex()
        val configMutex = Mutex()
    }
}
