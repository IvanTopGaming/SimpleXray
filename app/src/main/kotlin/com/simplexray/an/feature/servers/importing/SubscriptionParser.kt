package com.simplexray.an.feature.servers.importing

import com.simplexray.an.core.config.ownership.OwnedConfig

import android.content.Context
import java.util.Base64

object SubscriptionParser {
    fun parse(context: Context, rawBody: String): List<DetectedConfig> {
        return parse(rawBody) { ConfigFormatConverter.convertOrNull(context, it)?.getOrNull() }
    }

    fun parse(rawBody: String, convertLink: (String) -> DetectedConfig?): List<DetectedConfig> {
        val text = decodeIfBase64(rawBody.trim())
        if (text.startsWith("{") || text.startsWith("[")) return OwnedConfig.importProfiles(text)
        return text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                convertLink(line)
            }
            .flatMap { (name, source) ->
                val profiles = OwnedConfig.importProfiles(source)
                profiles.map { (profileName, config) ->
                    Pair(if (profiles.size == 1) name else "$name - $profileName", config)
                }
            }
            .toList()
    }

    private fun decodeIfBase64(body: String): String {
        if (body.startsWith("{") || body.startsWith("[") || body.contains("://")) return body
        return try {
            val clean = body.replace("-", "+").replace("_", "/").filterNot(Char::isWhitespace)
            val pad = (4 - clean.length % 4) % 4
            val decoded =
                String(Base64.getDecoder().decode(clean + "=".repeat(pad)), Charsets.UTF_8).trim()
            if (decoded.contains("://") || decoded.startsWith("{") || decoded.startsWith("["))
                decoded
            else body
        } catch (e: Exception) {
            body
        }
    }
}
