package com.simplexray.an.feature.settings.state.updates

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.simplexray.an.BuildConfig
import com.simplexray.an.R
import com.simplexray.an.app.state.MainViewUiEvent
import com.simplexray.an.core.network.http.await
import com.simplexray.an.core.network.socks.LocalProxyEndpoint
import com.simplexray.an.prefs.Preferences
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "MainViewModel"

internal class UpdatesController(
    private val application: Application,
    private val prefs: Preferences,
    private val scope: CoroutineScope,
    private val isServiceEnabled: () -> Boolean,
    private val sendEvent: (MainViewUiEvent) -> Unit,
) {
    private val _isCheckingForUpdates = MutableStateFlow(false)

    val isCheckingForUpdates: StateFlow<Boolean> = _isCheckingForUpdates.asStateFlow()

    private val _newVersionAvailable = MutableStateFlow<String?>(null)

    val newVersionAvailable: StateFlow<String?> = _newVersionAvailable.asStateFlow()

    fun checkForUpdates() {
        scope.launch(Dispatchers.IO) {
            _isCheckingForUpdates.value = true
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

            val request =
                Request.Builder()
                    .url(application.getString(R.string.source_url) + "/releases/latest")
                    .head()
                    .build()

            try {
                val (latestTag, comparison) =
                    client.newCall(request).await().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        val location = response.request.url
                        val releasePath = request.url.pathSegments.dropLast(1) + "tag"
                        require(
                            location.scheme == request.url.scheme &&
                                location.host == request.url.host &&
                                location.port == request.url.port &&
                                location.pathSegments.dropLast(1) == releasePath
                        ) {
                            "Invalid release tag location"
                        }
                        val tag = location.pathSegments.last()
                        val comparison =
                            requireNotNull(compareReleaseVersions(tag, BuildConfig.VERSION_NAME)) {
                                "Invalid release version"
                            }
                        tag to comparison
                    }
                Log.d(TAG, "Latest version tag: $latestTag")
                val updateAvailable = comparison > 0
                if (updateAvailable) {
                    _newVersionAvailable.value = latestTag
                } else {
                    sendEvent(
                        MainViewUiEvent.ShowSnackbar(
                            application.getString(R.string.no_new_version_available)
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                sendEvent(
                    MainViewUiEvent.ShowSnackbar(
                        application.getString(R.string.failed_to_check_for_updates) +
                            ": " +
                            e.message
                    )
                )
            } finally {
                _isCheckingForUpdates.value = false
            }
        }
    }

    fun downloadNewVersion(versionTag: String) {
        val url = application.getString(R.string.source_url) + "/releases/tag/$versionTag"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        application.startActivity(intent)
        _newVersionAvailable.value = null
    }

    fun clearNewVersionAvailable() {
        _newVersionAvailable.value = null
    }
}
