package com.simplexray.an.feature.subscriptions.data

import okhttp3.HttpUrl
import okhttp3.Request

internal data class SubscriptionHeader(val name: String, val value: String)

internal fun subscriptionHeadersRequest(
    origin: HttpUrl,
    request: Request,
    headers: List<SubscriptionHeader>,
): Request {
    val builder = request.newBuilder()
    headers.forEach { builder.removeHeader(it.name) }
    val target = request.url
    if (
        origin.scheme == target.scheme && origin.host == target.host && origin.port == target.port
    ) {
        headers.forEach { builder.header(it.name, it.value) }
    } else {
        builder.removeHeader("Authorization").removeHeader("Cookie")
    }
    return builder.build()
}
