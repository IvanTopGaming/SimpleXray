package com.simplexray.an.feature.settings.state.geodata

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.application
import com.simplexray.an.R
import com.simplexray.an.app.state.MainViewUiEvent
import com.simplexray.an.core.files.FileManager
import com.simplexray.an.core.network.http.await
import com.simplexray.an.core.network.socks.LocalProxyEndpoint
import com.simplexray.an.prefs.Preferences
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "MainViewModel"

internal class GeodataController(
    private val application: Application,
    private val prefs: Preferences,
    private val scope: CoroutineScope,
    private val fileManager: FileManager,
    private val isServiceEnabled: () -> Boolean,
    private val refreshRuleFileState: (String) -> Unit,
    private val updateSettingsState: () -> Unit,
    private val sendEvent: (MainViewUiEvent) -> Unit,
) {
    private val _geoipDownloadProgress = MutableStateFlow<String?>(null)

    val geoipDownloadProgress: StateFlow<String?> = _geoipDownloadProgress.asStateFlow()

    private var geoipDownloadJob: Job? = null

    private val _geositeDownloadProgress = MutableStateFlow<String?>(null)

    val geositeDownloadProgress: StateFlow<String?> = _geositeDownloadProgress.asStateFlow()

    private var geositeDownloadJob: Job? = null

    fun importRuleFile(uri: Uri, fileName: String) {
        scope.launch(Dispatchers.IO) {
            val sourceUrl = if (fileName == "geoip.dat") prefs.geoipUrl else prefs.geositeUrl
            val success = fileManager.importRuleFile(uri, fileName)
            if (success) {
                prefs.acknowledgeGeodata(fileName, sourceUrl)
                refreshRuleFileState(fileName)
                sendEvent(
                    MainViewUiEvent.ShowSnackbar(
                        "$fileName ${application.getString(R.string.import_success)}"
                    )
                )
            } else {
                sendEvent(
                    MainViewUiEvent.ShowSnackbar(application.getString(R.string.import_failed))
                )
            }
        }
    }

    fun cancelDownload(fileName: String) {
        scope.launch {
            if (fileName == "geoip.dat") {
                geoipDownloadJob?.cancel()
            } else {
                geositeDownloadJob?.cancel()
            }
            Log.d(TAG, "Download cancellation requested for $fileName")
        }
    }

    fun downloadRuleFile(url: String, fileName: String) {
        val currentJob = if (fileName == "geoip.dat") geoipDownloadJob else geositeDownloadJob
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Download already in progress for $fileName")
            return
        }

        val job =
            scope.launch(Dispatchers.IO) {
                val progressFlow =
                    if (fileName == "geoip.dat") {
                        _geoipDownloadProgress
                    } else {
                        _geositeDownloadProgress
                    }

                val client =
                    OkHttpClient.Builder()
                        .apply {
                            if (isServiceEnabled()) {
                                val endpoint = LocalProxyEndpoint.active(prefs)
                                proxy(
                                    Proxy(
                                        Proxy.Type.SOCKS,
                                        InetSocketAddress(endpoint.host, endpoint.port),
                                    )
                                )
                            }
                        }
                        .build()

                try {
                    progressFlow.value = application.getString(R.string.connecting)

                    val request = Request.Builder().url(url).build()
                    val call = client.newCall(request)
                    val response = call.await()

                    if (!response.isSuccessful) {
                        throw IOException("Failed to download file: ${response.code}")
                    }

                    val body = response.body ?: throw IOException("Response body is null")
                    val totalBytes = body.contentLength()
                    var bytesRead = 0L
                    var lastProgress = -1

                    body.byteStream().use { inputStream ->
                        val success =
                            fileManager.saveRuleFile(inputStream, fileName) { read ->
                                ensureActive()
                                bytesRead += read
                                if (totalBytes > 0) {
                                    val progress = (bytesRead * 100 / totalBytes).toInt()
                                    if (progress != lastProgress) {
                                        progressFlow.value =
                                            application.getString(R.string.downloading, progress)
                                        lastProgress = progress
                                    }
                                } else {
                                    if (lastProgress == -1) {
                                        progressFlow.value =
                                            application.getString(R.string.downloading_no_size)
                                        lastProgress = 0
                                    }
                                }
                            }
                        if (success) {
                            if (fileName == "geoip.dat") prefs.geoipUrl = url
                            else prefs.geositeUrl = url
                            refreshRuleFileState(fileName)
                            sendEvent(
                                MainViewUiEvent.ShowSnackbar(
                                    application.getString(R.string.download_success)
                                )
                            )
                        } else {
                            sendEvent(
                                MainViewUiEvent.ShowSnackbar(
                                    application.getString(R.string.download_failed)
                                )
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Download cancelled for $fileName")
                    sendEvent(
                        MainViewUiEvent.ShowSnackbar(
                            application.getString(R.string.download_cancelled)
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download rule file", e)
                    sendEvent(
                        MainViewUiEvent.ShowSnackbar(
                            application.getString(R.string.download_failed) + ": " + e.message
                        )
                    )
                } finally {
                    progressFlow.value = null
                    updateSettingsState()
                }
            }

        if (fileName == "geoip.dat") {
            geoipDownloadJob = job
        } else {
            geositeDownloadJob = job
        }

        job.invokeOnCompletion {
            if (fileName == "geoip.dat") {
                geoipDownloadJob = null
            } else {
                geositeDownloadJob = null
            }
        }
    }

    suspend fun cancelDownloadsAndJoin() {
        geoipDownloadJob?.cancelAndJoin()
        geositeDownloadJob?.cancelAndJoin()
    }
}
