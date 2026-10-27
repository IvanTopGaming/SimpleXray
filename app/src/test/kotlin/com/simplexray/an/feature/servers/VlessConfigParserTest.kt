package com.simplexray.an.feature.servers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.feature.servers.importing.VlessConfigParser
import org.junit.Assert.*
import org.junit.Test

class VlessConfigParserTest {
    private val endpoint = "vless://00000000-0000-0000-0000-000000000001@edge.example:443"

    @Test
    fun tlsWebsocketKeepsServerNameAlpnFingerprintAndTransport() {
        val config =
            VlessConfigParser.parse(
                "$endpoint?security=tls&type=ws&sni=front.example&fp=firefox&alpn=h2%2Chttp%2F1.1&host=ws.example&path=%2Fsocket%3Fed%3D2048#London%20%2B%20TLS"
            )
        assertEquals("London + TLS", config.first)
        val outbound = outbound(config.second)
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals("tls", stream["security"].asString)
        assertFalse(stream.has("realitySettings"))
        assertEquals("front.example", stream.getAsJsonObject("tlsSettings")["serverName"].asString)
        assertEquals("firefox", stream.getAsJsonObject("tlsSettings")["fingerprint"].asString)
        assertEquals(
            listOf("h2", "http/1.1"),
            stream.getAsJsonObject("tlsSettings").getAsJsonArray("alpn").map { it.asString },
        )
        assertEquals("/socket?ed=2048", stream.getAsJsonObject("wsSettings")["path"].asString)
        assertEquals("ws.example", stream.getAsJsonObject("wsSettings")["host"].asString)
        assertFalse(stream.getAsJsonObject("wsSettings").has("headers"))
        assertFalse(user(outbound).has("flow"))
    }

    @Test
    fun realityGrpcKeepsExplicitFlowKeyAndService() {
        val outbound =
            outbound(
                VlessConfigParser.parse(
                        "$endpoint?security=reality&type=grpc&sni=front.example&pbk=test-key&sid=abcd&spx=%2Fhello&flow=xtls-rprx-vision&serviceName=grpc-service&authority=grpc.example&mode=multi"
                    )
                    .second
            )
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals("reality", stream["security"].asString)
        assertFalse(stream.has("tlsSettings"))
        val reality = stream.getAsJsonObject("realitySettings")
        assertEquals("test-key", reality["publicKey"].asString)
        assertEquals("abcd", reality["shortId"].asString)
        assertEquals("/hello", reality["spiderX"].asString)
        assertEquals("chrome", reality["fingerprint"].asString)
        assertEquals("xtls-rprx-vision", user(outbound)["flow"].asString)
        assertEquals("grpc-service", stream.getAsJsonObject("grpcSettings")["serviceName"].asString)
        assertEquals("grpc.example", stream.getAsJsonObject("grpcSettings")["authority"].asString)
        assertTrue(stream.getAsJsonObject("grpcSettings")["multiMode"].asBoolean)
    }

    @Test
    fun missingSecurityAndFlowRemainUnencryptedWithoutInventingRealityCredentials() {
        for (link in listOf(endpoint, "$endpoint?security=none")) {
            val outbound = outbound(VlessConfigParser.parse(link).second)
            val stream = outbound.getAsJsonObject("streamSettings")
            assertEquals("none", stream["security"].asString)
            assertEquals("tcp", stream["network"].asString)
            assertFalse(stream.has("realitySettings"))
            assertFalse(stream.has("tlsSettings"))
            assertFalse(user(outbound).has("flow"))
            assertEquals("none", user(outbound)["encryption"].asString)
        }
    }

    @Test
    fun grpcLegacyPathAndIpv6EndpointAreDecoded() {
        val outbound =
            outbound(
                VlessConfigParser.parse(
                        "vless://custom%2Bid@[::1]:8443?security=tls&type=grpc&path=%2Fmy-service&peer=peer.example"
                    )
                    .second
            )
        val target =
            outbound.getAsJsonObject("settings").getAsJsonArray("vnext").single().asJsonObject
        assertEquals("::1", target["address"].asString)
        assertEquals(8443, target["port"].asInt)
        assertEquals("custom+id", user(outbound)["id"].asString)
        assertEquals(
            "my-service",
            outbound
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("grpcSettings")["serviceName"]
                .asString,
        )
        assertEquals(
            "peer.example",
            outbound
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("tlsSettings")["serverName"]
                .asString,
        )
    }

    @Test
    fun xhttpKeepsPathHostModeAndExtraAsJsonObject() {
        val outbound =
            outbound(
                VlessConfigParser.parse(
                        "$endpoint?encryption=none&security=tls&sni=cdn.example&fp=chrome&alpn=h2&type=xhttp&host=cdn.example&path=%2Fapi%2Fv2%2Fsync.json&mode=packet-up&extra=%7B%22noSSEHeader%22%3Atrue%2C%22xPaddingBytes%22%3A%22100-1000%22%2C%22sessionIDPlacement%22%3A%22query%22%2C%22sessionIDKey%22%3A%22sid%22%2C%22xmux%22%3A%7B%22maxConcurrency%22%3A%2216-32%22%7D%7D#CDN"
                    )
                    .second
            )
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals("xhttp", stream["network"].asString)
        assertEquals("cdn.example", stream.getAsJsonObject("tlsSettings")["serverName"].asString)
        val xhttp = stream.getAsJsonObject("xhttpSettings")
        assertEquals("/api/v2/sync.json", xhttp["path"].asString)
        assertEquals("cdn.example", xhttp["host"].asString)
        assertEquals("packet-up", xhttp["mode"].asString)
        val extra = xhttp.getAsJsonObject("extra")
        assertTrue(extra["noSSEHeader"].asBoolean)
        assertEquals("100-1000", extra["xPaddingBytes"].asString)
        assertEquals("query", extra["sessionIDPlacement"].asString)
        assertEquals("sid", extra["sessionIDKey"].asString)
        assertEquals("16-32", extra.getAsJsonObject("xmux")["maxConcurrency"].asString)
        assertFalse(stream.has("wsSettings"))
    }

    @Test
    fun xhttpWithoutOptionalFieldsAndSplithttpAliasUseDefaults() {
        for (type in listOf("xhttp", "splithttp")) {
            val stream =
                outbound(VlessConfigParser.parse("$endpoint?security=tls&type=$type").second)
                    .getAsJsonObject("streamSettings")
            assertEquals("xhttp", stream["network"].asString)
            val xhttp = stream.getAsJsonObject("xhttpSettings")
            assertEquals("/", xhttp["path"].asString)
            assertFalse(xhttp.has("host"))
            assertFalse(xhttp.has("mode"))
            assertFalse(xhttp.has("extra"))
        }
    }

    @Test
    fun tlsDropsRemovedAllowInsecureAndKeepsModernVerification() {
        val tls =
            stream(
                    "$endpoint?security=tls&allowInsecure=1&pcs=AA%3ABB&vcn=cert.example&ech=AEX%2B%2F%3D"
                )
                .getAsJsonObject("tlsSettings")
        assertFalse(tls.has("allowInsecure"))
        assertEquals("AA:BB", tls["pinnedPeerCertSha256"].asString)
        assertEquals("cert.example", tls["verifyPeerCertByName"].asString)
        assertEquals("AEX+/=", tls["echConfigList"].asString)
    }

    @Test
    fun realityKeepsPostQuantumVerifyKey() {
        val reality =
            stream("$endpoint?security=reality&pbk=key&pqv=mldsa-key&type=xhttp")
                .getAsJsonObject("realitySettings")
        assertEquals("mldsa-key", reality["mldsa65Verify"].asString)
    }

    @Test
    fun rawHttpHeaderObfuscationIsKept() {
        val stream =
            stream("$endpoint?type=tcp&headerType=http&host=a.example%2Cb.example&path=%2Fx%2C%2Fy")
        val header = stream.getAsJsonObject("rawSettings").getAsJsonObject("header")
        assertEquals("http", header["type"].asString)
        val request = header.getAsJsonObject("request")
        assertEquals(listOf("/x", "/y"), request.getAsJsonArray("path").map { it.asString })
        assertEquals(
            listOf("a.example", "b.example"),
            request.getAsJsonObject("headers").getAsJsonArray("Host").map { it.asString },
        )
        assertFalse(stream("$endpoint?type=tcp&headerType=none").has("rawSettings"))
    }

    @Test
    fun kcpLegacyHeaderAndSeedBecomeMkcpLegacyMasks() {
        fun masks(link: String) =
            stream(link).getAsJsonObject("finalmask").getAsJsonArray("udp").map {
                val mask = it.asJsonObject
                assertEquals("mkcp-legacy", mask["type"].asString)
                mask.getAsJsonObject("settings")?.entrySet()?.associate { (key, value) ->
                    key to value.asString
                } ?: emptyMap()
            }
        assertEquals(
            listOf(mapOf("value" to "secret"), mapOf("header" to "wechat")),
            masks("$endpoint?type=kcp&headerType=wechat-video&seed=secret"),
        )
        assertEquals(listOf(emptyMap<String, String>()), masks("$endpoint?type=mkcp"))
        assertEquals(
            listOf(emptyMap(), mapOf("header" to "dns", "value" to "dns.example")),
            masks("$endpoint?type=kcp&headerType=dns&host=dns.example"),
        )
        assertEquals(
            listOf(emptyMap(), mapOf("header" to "srtp")),
            masks("$endpoint?type=kcp&headerType=srtp&host=ignored.example"),
        )
        assertEquals("kcp", stream("$endpoint?type=mkcp")["network"].asString)
        assertFalse(stream("$endpoint?type=kcp").has("kcpSettings"))
    }

    @Test
    fun unencodedNamesWithSpacesAndEmojiAreAccepted() {
        assertEquals(
            "🇩🇪 Berlin #1",
            VlessConfigParser.parse("$endpoint?security=none#🇩🇪 Berlin #1").first,
        )
    }

    @Test
    fun invalidLinksFailBeforeProducingAProfile() {
        for (link in
            listOf(
                "vless://edge.example",
                "vless://id@:443",
                "$endpoint?security=reality",
                "$endpoint?security=unknown",
                "vless://id@edge.example:70000",
                "trojan://id@edge.example",
                "$endpoint?type=xhttp&extra=%7Bbroken",
                "$endpoint?type=xhttp&extra=%5B1%2C2%5D",
                "$endpoint?type=xhttp&extra=plain",
                "$endpoint?type=h2",
                "$endpoint?type=http",
                "$endpoint?type=quic",
                "$endpoint?type=h3",
                "$endpoint?type=carrier-pigeon",
                "$endpoint?type=kcp&headerType=morse",
            )) {
            assertTrue("Accepted $link", runCatching { VlessConfigParser.parse(link) }.isFailure)
        }
    }

    private fun outbound(config: String) =
        JsonParser.parseString(config)
            .asJsonObject
            .getAsJsonArray("outbounds")
            .single()
            .asJsonObject

    private fun stream(link: String): JsonObject =
        outbound(VlessConfigParser.parse(link).second).getAsJsonObject("streamSettings")

    private fun user(outbound: JsonObject) =
        outbound
            .getAsJsonObject("settings")
            .getAsJsonArray("vnext")
            .single()
            .asJsonObject
            .getAsJsonArray("users")
            .single()
            .asJsonObject
}
