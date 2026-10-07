package com.simplexray.an.feature.subscriptions

import com.simplexray.an.feature.servers.importing.SubscriptionParser
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SubscriptionParserTest {
    private val json =
        """{"remarks":"One","dns":{"servers":["9.9.9.9"]},"outbounds":[{"protocol":"trojan","settings":{"servers":[{"address":"one.example","port":443,"password":"secret"}]}}]}"""

    @Test
    fun plainAndBase64JsonUseStructuredImportBeforeLineConversion() {
        for (body in listOf(json, Base64.getEncoder().encodeToString(json.toByteArray()))) {
            val result = SubscriptionParser.parse(body) { error("JSON was sent to link converter") }
            assertEquals(listOf("One"), result.map { it.first })
            assertFalse(result.single().second.contains("9.9.9.9"))
        }
    }

    @Test
    fun plainAndUrlSafeBase64LinkListsKeepLinkConversion() {
        val body = "vless://one\n\ntrojan://two"
        val convert: (String) -> Pair<String, String>? = { line -> Pair(line, json) }
        val plain = SubscriptionParser.parse(body, convert)
        val encoded =
            SubscriptionParser.parse(
                Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray()),
                convert,
            )
        assertEquals(plain, encoded)
        assertEquals(listOf("vless://one", "trojan://two"), plain.map { it.first })
        assertFalse(plain.any { it.second.contains("9.9.9.9") })
    }

    @Test
    fun malformedJsonNeverFallsBackToLineConverter() {
        val result = runCatching {
            SubscriptionParser.parse("{broken\nvless://one") {
                error("Malformed JSON reached URI converter")
            }
        }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
