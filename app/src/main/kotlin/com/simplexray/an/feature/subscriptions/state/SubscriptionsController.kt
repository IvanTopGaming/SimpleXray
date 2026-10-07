package com.simplexray.an.feature.subscriptions.state

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.application
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.routing.state.RoutingPresetController
import com.simplexray.an.feature.subscriptions.data.SubscriptionManager
import com.simplexray.an.feature.subscriptions.data.SubscriptionRefreshScheduler
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.prefs.PrefsContract
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SubscriptionsController(
    private val application: Application,
    private val prefs: Preferences,
    private val scope: CoroutineScope,
    private val subscriptionManager: SubscriptionManager,
    private val configUpdateMutex: Mutex,
    private val routingPresets: RoutingPresetController,
    private val refreshConfigFileList: () -> Unit,
    private val selectedConfigFile: () -> File?,
    private val updateSelectedConfigFile: (File?) -> Unit,
    private val isServiceEnabled: () -> Boolean,
    private val stopTProxyService: () -> Unit,
) {
    private val subscriptionRefreshMutex = Mutex()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())

    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _autoUpdateSubscriptions = MutableStateFlow(prefs.autoUpdateSubscriptions)

    val autoUpdateSubscriptions: StateFlow<Boolean> = _autoUpdateSubscriptions.asStateFlow()

    private val subscriptionObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                when (uri?.lastPathSegment) {
                    "Subscriptions" -> {
                        refreshSubscriptions()
                        refreshConfigFileList()
                    }
                    "AutoUpdateSubscriptions",
                    Preferences.SUBSCRIPTION_UPDATE_INTERVAL -> {
                        _autoUpdateSubscriptions.value = prefs.autoUpdateSubscriptions
                        SubscriptionRefreshScheduler.reconcile(application)
                    }
                }
            }
        }

    private val _subscriptionSync = MutableStateFlow<Map<String, SubscriptionSyncState>>(emptyMap())

    val subscriptionSync: StateFlow<Map<String, SubscriptionSyncState>> =
        _subscriptionSync.asStateFlow()

    private val _subscriptionByFile = MutableStateFlow<Map<String, String>>(emptyMap())

    val subscriptionByFile: StateFlow<Map<String, String>> = _subscriptionByFile.asStateFlow()

    fun refreshSubscriptions() =
        scope.launch(Dispatchers.IO) {
            subscriptionRefreshMutex.withLock {
                val subs = prefs.subscriptions
                _subscriptions.value = subs
                _subscriptionByFile.value = buildMap {
                    subs.forEach { sub -> sub.files.forEach { put(it, sub.id) } }
                }
                SubscriptionRefreshScheduler.reconcile(application)
            }
        }

    fun updateAutoUpdateSubscriptions(enabled: Boolean) {
        prefs.autoUpdateSubscriptions = enabled
        _autoUpdateSubscriptions.value = enabled
        SubscriptionRefreshScheduler.reconcile(application)
    }

    fun addSubscription(name: String, url: String) {
        scope.launch(Dispatchers.IO) {
            importSubscription(name, url)
        }
    }

    suspend fun importSubscription(name: String, url: String): Boolean =
        routingPresets.routingPresetOperation {
            configUpdateMutex.withLock {
                val embeddedPreset = RoutingPreset.detect(url.trim())
                if (embeddedPreset != null) {
                    routingPresets.stageRoutingPreset(embeddedPreset)
                    return@withLock true
                }
                val response =
                    try {
                        Result.success(subscriptionManager.fetchForImport(url))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                val preset = response.getOrNull()?.body?.let(RoutingPreset::detect)
                if (preset != null) {
                    routingPresets.stageRoutingPreset(preset)
                    return@withLock true
                }
                val created = subscriptionManager.create(name, url)
                refreshSubscriptions()
                setSync(created.id, SubscriptionSyncState(syncing = true))
                val result = subscriptionManager.refresh(created.id, response)
                val error = result.exceptionOrNull()?.message
                if (error == null) clearSync(created.id)
                else setSync(created.id, SubscriptionSyncState(error = error))
                refreshSubscriptions()
                refreshConfigFileList()
                true
            }
        }

    fun syncSubscription(id: String) {
        if (_subscriptionSync.value[id]?.syncing == true) return
        scope.launch(Dispatchers.IO) {
            configUpdateMutex.withLock {
                val previousFiles = prefs.subscriptions.find { it.id == id }?.files.orEmpty()
                setSync(id, SubscriptionSyncState(syncing = true))
                val result = subscriptionManager.refresh(id)
                result.onSuccess { applySubscriptionChange(previousFiles, it.files) }
                val error = result.exceptionOrNull()?.message
                if (error == null) clearSync(id)
                else setSync(id, SubscriptionSyncState(error = error))
                refreshSubscriptions()
                refreshConfigFileList()
            }
        }
    }

    fun updateSubscriptionUrl(id: String, url: String) {
        scope.launch(Dispatchers.IO) {
            val updated = configUpdateMutex.withLock {
                subscriptionManager.updateUrl(id, url).also { refreshSubscriptions() }
            }
            if (updated) syncSubscription(id)
        }
    }

    fun deleteSubscription(id: String) {
        scope.launch(Dispatchers.IO) {
            configUpdateMutex.withLock {
                val previousFiles = prefs.subscriptions.find { it.id == id }?.files.orEmpty()
                if (subscriptionManager.delete(id)) {
                    applySubscriptionChange(previousFiles, emptyList())
                }
                clearSync(id)
                refreshSubscriptions()
                refreshConfigFileList()
            }
        }
    }

    private suspend fun applySubscriptionChange(
        previousFiles: List<String>,
        updatedFiles: List<String>,
    ) {
        withContext(Dispatchers.Main) {
            val selected = selectedConfigFile() ?: return@withContext
            if (selected.name !in previousFiles) return@withContext
            if (selected.name !in updatedFiles) {
                updateSelectedConfigFile(null)
                if (isServiceEnabled()) stopTProxyService()
            }
        }
    }

    private fun setSync(id: String, state: SubscriptionSyncState) {
        _subscriptionSync.update { it + (id to state) }
    }

    private fun clearSync(id: String) {
        _subscriptionSync.update { it - id }
    }

    fun registerObserver() {
        application.contentResolver.registerContentObserver(
            PrefsContract.PrefsEntry.CONTENT_URI,
            true,
            subscriptionObserver,
        )
    }

    fun unregisterObserver() {
        application.contentResolver.unregisterContentObserver(subscriptionObserver)
    }
}
