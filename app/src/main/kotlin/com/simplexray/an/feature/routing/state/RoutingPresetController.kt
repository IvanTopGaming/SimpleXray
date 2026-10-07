package com.simplexray.an.feature.routing.state

import com.simplexray.an.app.state.MainViewUiEvent
import com.simplexray.an.core.files.FileManager
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.subscriptions.data.SubscriptionManager
import com.simplexray.an.prefs.Preferences
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class RoutingPresetController(
    private val prefs: Preferences,
    private val fileManager: FileManager,
    private val subscriptionManager: SubscriptionManager,
    private val configUpdateMutex: Mutex,
    private val routingEditor: RoutingEditor,
    private val refreshConfigFileList: () -> Unit,
    private val updateSettingsState: () -> Unit,
    private val cancelDownloadsAndJoin: suspend () -> Unit,
    private val sendEvent: (MainViewUiEvent) -> Unit,
) {
    private val routingPresetOperations = Mutex()

    private val _pendingRoutingPreset = MutableStateFlow<RoutingPreset?>(null)

    val pendingRoutingPreset = _pendingRoutingPreset.asStateFlow()

    private val _routingPresetBusy = MutableStateFlow(false)

    val routingPresetBusy = _routingPresetBusy.asStateFlow()

    private val _routingPresetError = MutableStateFlow<String?>(null)

    val routingPresetError = _routingPresetError.asStateFlow()

    suspend fun importServer(content: String, name: String? = null): Boolean =
        routingPresetOperation {
            configUpdateMutex.withLock {
                val text = content.trim()
                val preset =
                    if (text.toHttpUrlOrNull() != null) {
                        RoutingPreset.detect(subscriptionManager.fetchForImport(text, false).body)
                            ?: throw IllegalArgumentException("По ссылке не найден пресет роутинга")
                    } else RoutingPreset.detect(text)
                if (preset != null) stageRoutingPreset(preset)
                else {
                    fileManager.importConfigFromContent(text, name) ?: return@withLock false
                    refreshConfigFileList()
                }
                true
            }
        }

    suspend fun routingPresetOperation(action: suspend () -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            routingPresetOperations.withLock {
                _routingPresetBusy.value = true
                _routingPresetError.value = null
                try {
                    action()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message =
                        error.message?.take(300) ?: "Не удалось обработать пресет роутинга"
                    _routingPresetError.value = message
                    sendEvent(MainViewUiEvent.ShowSnackbar(message))
                    false
                } finally {
                    _routingPresetBusy.value = false
                }
            }
        }

    suspend fun stageRoutingPreset(preset: RoutingPreset) {
        require(_pendingRoutingPreset.value == null) { "Сначала заверши открытый импорт пресета" }
        preset.validate()
        currentCoroutineContext().ensureActive()
        _pendingRoutingPreset.value = preset
    }

    suspend fun importRoutingPreset(content: String): Boolean = routingPresetOperation {
        val input = content.trim()
        require(input.isNotEmpty()) { "Буфер обмена пуст" }
        val text =
            if (input.toHttpUrlOrNull() != null)
                subscriptionManager.fetchForImport(input, false).body
            else input
        currentCoroutineContext().ensureActive()
        stageRoutingPreset(
            RoutingPreset.detect(text)
                ?: throw IllegalArgumentException("В буфере обмена нет пресета роутинга")
        )
        true
    }

    suspend fun exportRoutingPreset(): String? {
        var result: String? = null
        routingPresetOperation {
            result = prefs.readRoutingPreset().encodeLink()
            true
        }
        return result
    }

    fun cancelRoutingPresetImport() {
        if (_routingPresetBusy.value) return
        _pendingRoutingPreset.value = null
        _routingPresetError.value = null
    }

    suspend fun applyRoutingPreset(): Boolean = routingPresetOperation {
        configUpdateMutex.withLock {
            val preset =
                _pendingRoutingPreset.value
                    ?: throw IllegalArgumentException("Выбери пресет для импорта")
            preset.validate()
            cancelDownloadsAndJoin()
            currentCoroutineContext().ensureActive()
            prefs.applyRoutingPreset(preset)
            routingEditor.reload()
            updateSettingsState()
            _pendingRoutingPreset.value = null
            sendEvent(MainViewUiEvent.ShowSnackbar("Пресет роутинга импортирован"))
            true
        }
    }
}
