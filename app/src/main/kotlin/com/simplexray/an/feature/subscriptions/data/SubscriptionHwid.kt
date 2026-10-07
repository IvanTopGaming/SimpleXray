package com.simplexray.an.feature.subscriptions.data

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import okhttp3.HttpUrl
import okhttp3.Request

internal fun subscriptionHwidRequest(
    origin: HttpUrl,
    request: Request,
    identifier: String?,
): Request {
    val builder = request.newBuilder().removeHeader("x-hwid")
    val target = request.url
    if (
        identifier != null &&
            origin.scheme == target.scheme &&
            origin.host == target.host &&
            origin.port == target.port
    ) {
        builder.header("x-hwid", identifier)
    }
    return builder.build()
}

@Synchronized
internal fun subscriptionInstallationId(directory: File): String =
    RandomAccessFile(File(directory, "subscription-installation-id"), "rw").use { file ->
        file.channel.lock().use {
            val stored =
                file.readLine()?.let { value ->
                    runCatching { UUID.fromString(value).toString() }.getOrNull()
                }
            stored
                ?: UUID.randomUUID().toString().also { identifier ->
                    file.setLength(0)
                    file.seek(0)
                    file.writeBytes(identifier)
                    file.fd.sync()
                }
        }
    }
