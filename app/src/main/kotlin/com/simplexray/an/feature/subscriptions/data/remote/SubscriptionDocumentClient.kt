package com.simplexray.an.feature.subscriptions.data.remote

import com.simplexray.an.core.network.socks.LocalProxyEndpoint
import com.simplexray.an.feature.subscriptions.data.SubscriptionClientIdentity
import com.simplexray.an.feature.subscriptions.data.SubscriptionIdentityField
import com.simplexray.an.feature.subscriptions.data.SubscriptionManager
import com.simplexray.an.feature.subscriptions.data.subscriptionHeadersRequest
import com.simplexray.an.feature.subscriptions.data.subscriptionHwidRequest
import com.simplexray.an.feature.subscriptions.data.subscriptionTitle
import com.simplexray.an.prefs.Preferences
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

internal class SubscriptionDocumentClient(
    private val prefs: Preferences,
    private val isServiceEnabled: () -> Boolean,
) {
    fun fetchDocument(
        url: String,
        subscriptionIdentity: Boolean,
        bounded: Boolean,
    ): SubscriptionManager.Response {
        val request = Request.Builder().url(url).build()
        val sendHwid = subscriptionIdentity && prefs.subscriptionSendHwid
        val identity =
            if (subscriptionIdentity) {
                val overrides =
                    SubscriptionClientIdentity.parse(prefs.subscriptionClientIdentityJson)
                val installationId =
                    if (sendHwid && overrides[SubscriptionIdentityField.HWID].isEmpty())
                        prefs.subscriptionInstallationId
                    else ""
                overrides.withDefaults(SubscriptionClientIdentity.deviceDefaults(installationId))
            } else null
        val headers = identity?.headers().orEmpty()
        val identifier = if (sendHwid) identity?.get(SubscriptionIdentityField.HWID) else null
        val useProxy = isServiceEnabled()
        val endpoint = if (useProxy) LocalProxyEndpoint.active(prefs) else null
        val client =
            OkHttpClient.Builder()
                .apply {
                    callTimeout(30, TimeUnit.SECONDS)
                    if (subscriptionIdentity)
                        addNetworkInterceptor { chain ->
                            chain.proceed(
                                subscriptionHwidRequest(
                                    request.url,
                                    subscriptionHeadersRequest(
                                        request.url,
                                        chain.request(),
                                        headers,
                                    ),
                                    identifier,
                                )
                            )
                        }
                    if (endpoint != null) {
                        java.net.Authenticator.setDefault(
                            object : java.net.Authenticator() {
                                override fun getPasswordAuthentication():
                                    java.net.PasswordAuthentication? {
                                    return LocalProxyEndpoint.active(prefs)
                                        .authentication(
                                            requestingHost,
                                            requestingPort,
                                            requestingProtocol,
                                        )
                                }
                            }
                        )
                        proxy(
                            Proxy(Proxy.Type.SOCKS, InetSocketAddress(endpoint.host, endpoint.port))
                        )
                    }
                }
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val responseBody = response.body ?: throw IOException("Empty body")
            if (
                bounded &&
                    (responseBody.contentLength() > 8L * 1024 * 1024 ||
                        responseBody.source().request(8L * 1024 * 1024 + 1))
            ) {
                throw IOException("Файл импорта превышает 8 МиБ")
            }
            val body = responseBody.string()
            return SubscriptionManager.Response(
                body,
                subscriptionTitle(
                    url,
                    response.header("Profile-Title"),
                    response.header("Content-Disposition"),
                ),
                response.header("subscription-userinfo"),
            )
        }
    }
}
