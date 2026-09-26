package com.simplexray.an.common.configFormat

import android.content.Context
import java.util.Base64

object SubscriptionParser {
    fun parse(context: Context, rawBody: String): List<DetectedConfig> {
        val text = decodeIfBase64(rawBody.trim())
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                ConfigFormatConverter.convertOrNull(context, line)?.getOrNull()
            }
            .toList()
    }

    private fun decodeIfBase64(body: String): String {
        if (body.contains("://")) return body
        return try {
            val clean = body.replace("-", "+").replace("_", "/").replace("\n", "").replace("\r", "")
            val pad = (4 - clean.length % 4) % 4
            val decoded = String(Base64.getDecoder().decode(clean + "=".repeat(pad)))
            if (decoded.contains("://")) decoded else body
        } catch (e: Exception) {
            body
        }
    }
}
