package com.simplexray.an.feature.servers.state

import android.app.Application
import android.util.Log
import androidx.lifecycle.application
import com.simplexray.an.R
import com.simplexray.an.app.state.MainViewUiEvent
import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.core.files.FileManager
import com.simplexray.an.core.network.probe.ServerProbe
import com.simplexray.an.feature.servers.data.ServerChecks
import com.simplexray.an.feature.servers.model.ProbeMethod
import com.simplexray.an.feature.servers.model.ServerDetails
import com.simplexray.an.feature.servers.model.serverDetails
import com.simplexray.an.prefs.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "MainViewModel"

internal class ConfigFilesController(
    private val application: Application,
    private val prefs: Preferences,
    private val scope: CoroutineScope,
    private val fileManager: FileManager,
    private val configUpdateMutex: Mutex,
    private val isServiceEnabled: () -> Boolean,
    private val sendEvent: (MainViewUiEvent) -> Unit,
) {
    private val serverChecker =
        ServerChecks(
            scope,
            readConfig = { file -> configUpdateMutex.withLock { file.readText() } },
            probe = { config ->
                ServerProbe(application)
                    .check(
                        AppConfig(prefs).build(config),
                        prefs.connectivityTestTarget,
                        prefs.connectivityTestTimeout.coerceIn(1, 30000),
                        selectedProbeMethod(),
                    )
            },
        )

    val serverChecks = serverChecker.state

    private fun selectedProbeMethod() = runCatching {
        ProbeMethod.valueOf(prefs.probeMethod)
    }
        .getOrDefault(ProbeMethod.HTTP_GET)

    fun checkServers(files: List<File>) {
        val target = prefs.connectivityTestTarget
        val timeout = prefs.connectivityTestTimeout.coerceIn(1, 30000)
        val method = selectedProbeMethod()
        val config =
            try {
                AppConfig(prefs)
            } catch (error: IllegalArgumentException) {
                sendEvent(
                    MainViewUiEvent.ShowSnackbar(error.message ?: "Проверь настройки DNS и ядра")
                )
                return
            }
        serverChecker.start(files) { source ->
            ServerProbe(application).check(config.build(source), target, timeout, method)
        }
    }

    fun cancelServerChecks() = serverChecker.cancel()

    private val _configFiles = MutableStateFlow<List<File>>(emptyList())

    val configFiles: StateFlow<List<File>> = _configFiles.asStateFlow()

    private val _serverDetails = MutableStateFlow<Map<File, ServerDetails>>(emptyMap())

    val serverDetails: StateFlow<Map<File, ServerDetails>> = _serverDetails.asStateFlow()

    private val _selectedConfigFile = MutableStateFlow<File?>(null)

    val selectedConfigFile: StateFlow<File?> = _selectedConfigFile.asStateFlow()

    suspend fun createConfigFile(): String? {
        val filePath = fileManager.createConfigFile()
        if (filePath == null) {
            sendEvent(
                MainViewUiEvent.ShowSnackbar(application.getString(R.string.create_config_failed))
            )
        } else {
            refreshConfigFileList()
        }
        return filePath
    }

    suspend fun importConfigFromClipboard(): String? {
        val filePath = fileManager.importConfigFromClipboard()
        if (filePath == null) {
            sendEvent(MainViewUiEvent.ShowSnackbar(application.getString(R.string.import_failed)))
        } else {
            refreshConfigFileList()
        }
        return filePath
    }

    suspend fun deleteConfigFile(file: File, callback: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            if (
                isServiceEnabled() &&
                    _selectedConfigFile.value != null &&
                    _selectedConfigFile.value == file
            ) {
                sendEvent(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.config_in_use))
                )
                Log.w(TAG, "Attempted to delete selected config file: ${file.name}")
                return@launch
            }

            val success = fileManager.deleteConfigFile(file)
            if (success) {
                withContext(Dispatchers.Main) {
                    refreshConfigFileList()
                }
            } else {
                sendEvent(MainViewUiEvent.ShowSnackbar(application.getString(R.string.delete_fail)))
            }
            callback()
        }
    }

    fun refreshConfigFileList() =
        scope.launch(Dispatchers.IO) {
            configUpdateMutex.withLock {
                val filesDir = application.filesDir
                val actualFiles =
                    filesDir
                        .listFiles { file -> file.isFile && file.name.endsWith(".json") }
                        ?.toList() ?: emptyList()
                val actualFilesByName = actualFiles.associateBy { it.name }
                val savedOrder = prefs.configFilesOrder

                val newOrder = mutableListOf<File>()
                val remainingActualFileNames = actualFilesByName.toMutableMap()

                savedOrder.forEach { filename ->
                    actualFilesByName[filename]?.let { file ->
                        newOrder.add(file)
                        remainingActualFileNames.remove(filename)
                    }
                }

                newOrder.addAll(remainingActualFileNames.values.filter { it !in newOrder })

                val contents =
                    newOrder
                        .mapNotNull { file ->
                            runCatching { file.absolutePath to file.readText() }.getOrNull()
                        }
                        .toMap()
                _serverDetails.value = newOrder.associateWith { file ->
                    serverDetails(file.name, contents[file.absolutePath])
                }
                _configFiles.value = newOrder
                serverChecker.invalidate(contents)
                val newNames = newOrder.map { it.name }
                if (savedOrder != newNames) prefs.configFilesOrder = newNames

                val currentSelectedPath = prefs.selectedConfigPath
                var fileToSelect: File? = null

                if (currentSelectedPath != null) {
                    val foundSelected = newOrder.find { it.absolutePath == currentSelectedPath }
                    if (foundSelected != null) {
                        fileToSelect = foundSelected
                    }
                }

                _selectedConfigFile.value = fileToSelect
                if (currentSelectedPath != fileToSelect?.absolutePath)
                    prefs.selectedConfigPath = fileToSelect?.absolutePath
            }
        }

    fun updateSelectedConfigFile(file: File?) {
        _selectedConfigFile.value = file
        prefs.selectedConfigPath = file?.absolutePath
    }

    fun testConnectivity() {
        val selected = prefs.selectedConfigPath?.let(::File)
        if (selected == null) {
            sendEvent(MainViewUiEvent.ShowSnackbar("Сначала выбери сервер"))
        } else checkServers(listOf(selected))
    }

    fun resetServerChecks() = serverChecker.reset()
}
