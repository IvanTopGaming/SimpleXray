package com.simplexray.an.feature.subscriptions.data

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

fun subscriptionTitle(url: String, profileTitle: String?, contentDisposition: String?): String {
    fun clean(value: String) = value.replace(Regex("[\\p{Cntrl}]"), "").trim().take(160)
    var title = profileTitle.orEmpty().trim()
    if (title.startsWith("base64:", ignoreCase = true)) {
        title =
            runCatching {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(Base64.getDecoder().decode(title.substring(7))))
                        .toString()
                }
                .getOrDefault("")
    }
    clean(title)
        .takeIf { it.isNotEmpty() }
        ?.let {
            return it
        }
    val disposition = contentDisposition.orEmpty()
    val encoded =
        Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(disposition)
            ?.groupValues
            ?.get(1)
    val filename =
        Regex("(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", RegexOption.IGNORE_CASE)
            .find(disposition)
    title = filename?.groupValues?.let { it[1].ifEmpty { it[2] } }.orEmpty()
    if (encoded != null)
        title =
            runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }
                .getOrDefault(title)
    return clean(title).replace(Regex("\\.(txt|yaml|yml)$", RegexOption.IGNORE_CASE), "").ifEmpty {
        URI(url).host.orEmpty()
    }
}
