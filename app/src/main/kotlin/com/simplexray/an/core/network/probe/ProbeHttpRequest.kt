package com.simplexray.an.core.network.probe

import com.simplexray.an.feature.servers.model.ProbeMethod
import okhttp3.HttpUrl

internal fun buildProbeHttpRequest(url: HttpUrl, credentials: String, method: ProbeMethod): String {
    require(method != ProbeMethod.TCP)
    val verb = if (method == ProbeMethod.HTTP_HEAD) "HEAD" else "GET"
    val target = url.newBuilder().fragment(null).username("").password("").build()
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    return "$verb $target HTTP/1.1\r\nHost: $host:${url.port}\r\nProxy-Authorization: $credentials\r\nConnection: close\r\n\r\n"
}
