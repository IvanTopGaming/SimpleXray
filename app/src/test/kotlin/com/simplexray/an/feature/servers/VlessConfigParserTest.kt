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
        assertEquals(
            "ws.example",
            stream.getAsJsonObject("wsSettings").getAsJsonObject("headers")["Host"].asString,
        )
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
    fun invalidLinksFailBeforeProducingAProfile() {
        for (link in
            listOf(
                "vless://edge.example",
                "vless://id@:443",
                "$endpoint?security=reality",
                "$endpoint?security=unknown",
                "vless://id@edge.example:70000",
                "trojan://id@edge.example",
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
