package com.simplexray.an.feature.servers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.feature.servers.importing.ConfigFormatConverter
import com.simplexray.an.feature.servers.importing.Hysteria2ConfigParser
import com.simplexray.an.feature.servers.importing.ShadowsocksConfigParser
import com.simplexray.an.feature.servers.importing.SocksConfigParser
import com.simplexray.an.feature.servers.importing.TrojanConfigParser
import com.simplexray.an.feature.servers.importing.VmessConfigParser
import com.simplexray.an.feature.servers.importing.WireguardConfigParser
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ShareLinkParsersTest {
    private val privateKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    private val publicKey = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="
    private val pin = "AA:".repeat(31) + "AA"

    @Test
    fun registryRoutesEverySupportedScheme() {
        for (scheme in
            listOf("vless", "vmess", "ss", "trojan", "hysteria2", "hy2", "socks", "socks5", "wireguard", "wg")) {
            assertEquals(
                scheme,
                1,
                ConfigFormatConverter.knownImplementations.count { it.detect("$scheme://x") },
            )
        }
        assertTrue(
            ConfigFormatConverter.knownImplementations.none { it.detect("https://sub.example/a") }
        )
    }

    @Test
    fun vmessWebsocketKeepsTlsDetailsAndModernHost() {
        val (name, config) =
            VmessConfigParser.parse(
                vmess(
                    """{"v":"2","ps":"VM","add":"vm.example","port":"8443","id":"uuid","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example","path":"/ws","tls":"tls","sni":"","alpn":"h2,http/1.1","fp":"chrome"}"""
                )
            )
        assertEquals("VM", name)
        val outbound = outbound(config)
        assertEquals("vmess", outbound["protocol"].asString)
        val target = outbound.getAsJsonObject("settings").getAsJsonArray("vnext").single().asJsonObject
        assertEquals("vm.example", target["address"].asString)
        assertEquals(8443, target["port"].asInt)
        val user = target.getAsJsonArray("users").single().asJsonObject
        assertEquals("uuid", user["id"].asString)
        assertEquals("auto", user["security"].asString)
        assertFalse(user.has("alterId"))
        val stream = outbound.getAsJsonObject("streamSettings")
        val tls = stream.getAsJsonObject("tlsSettings")
        assertEquals("cdn.example", tls["serverName"].asString)
        assertEquals("chrome", tls["fingerprint"].asString)
        assertEquals(listOf("h2", "http/1.1"), tls.getAsJsonArray("alpn").map { it.asString })
        val ws = stream.getAsJsonObject("wsSettings")
        assertEquals("/ws", ws["path"].asString)
        assertEquals("cdn.example", ws["host"].asString)
        assertFalse(ws.has("headers"))
    }

    @Test
    fun vmessGrpcAndXhttpReadModeFromHeaderType() {
        val grpc =
            stream(
                VmessConfigParser.parse(
                        vmess(
                            """{"add":"vm.example","port":443,"id":"uuid","net":"grpc","type":"multi","host":"auth.example","path":"svc","tls":"tls"}"""
                        )
                    )
                    .second
            )
        assertEquals("svc", grpc.getAsJsonObject("grpcSettings")["serviceName"].asString)
        assertEquals("auth.example", grpc.getAsJsonObject("grpcSettings")["authority"].asString)
        assertTrue(grpc.getAsJsonObject("grpcSettings")["multiMode"].asBoolean)
        val xhttp =
            stream(
                    VmessConfigParser.parse(
                            vmess(
                                """{"add":"vm.example","port":443,"id":"uuid","net":"xhttp","type":"stream-up","host":"x.example","path":"/x"}"""
                            )
                        )
                        .second
                )
                .getAsJsonObject("xhttpSettings")
        assertEquals("stream-up", xhttp["mode"].asString)
        assertEquals("x.example", xhttp["host"].asString)
        assertEquals("/x", xhttp["path"].asString)
    }

    @Test
    fun vmessRejectsRemovedTransportsAndBrokenPayloads() {
        for (link in
            listOf(
                vmess("""{"add":"vm.example","port":443,"id":"uuid","net":"h2"}"""),
                vmess("""{"add":"vm.example","port":443,"id":"uuid","net":"quic"}"""),
                vmess("""{"port":443,"id":"uuid"}"""),
                vmess("""{"add":"vm.example","port":443}"""),
                vmess("""[1]"""),
                "vmess://%%%",
            )) {
            assertTrue(link, runCatching { VmessConfigParser.parse(link) }.isFailure)
        }
    }

    @Test
    fun trojanUsesSharedTransportAndDefaultsToTls() {
        val outbound =
            outbound(
                TrojanConfigParser.parse(
                        "trojan://p%40ss@tr.example?type=grpc&serviceName=svc&mode=multi&sni=front.example&fp=firefox&alpn=h2#TR"
                    )
                    .second
            )
        val server = outbound.getAsJsonObject("settings").getAsJsonArray("servers").single().asJsonObject
        assertEquals("p@ss", server["password"].asString)
        assertEquals(443, server["port"].asInt)
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals("tls", stream["security"].asString)
        assertEquals("front.example", stream.getAsJsonObject("tlsSettings")["serverName"].asString)
        assertEquals("firefox", stream.getAsJsonObject("tlsSettings")["fingerprint"].asString)
        assertEquals("svc", stream.getAsJsonObject("grpcSettings")["serviceName"].asString)
        assertTrue(stream.getAsJsonObject("grpcSettings")["multiMode"].asBoolean)
    }

    @Test
    fun trojanHonoursRealityAndPlainSecurity() {
        val reality =
            stream(
                TrojanConfigParser.parse(
                        "trojan://pw@tr.example:8443?security=reality&pbk=key&sid=ab&sni=cover.example"
                    )
                    .second
            )
        assertEquals("reality", reality["security"].asString)
        assertEquals("key", reality.getAsJsonObject("realitySettings")["publicKey"].asString)
        assertFalse(reality.has("tlsSettings"))
        val plain = stream(TrojanConfigParser.parse("trojan://pw@tr.example?security=none").second)
        assertEquals("none", plain["security"].asString)
        assertFalse(plain.has("tlsSettings"))
        assertTrue(runCatching { TrojanConfigParser.parse("trojan://tr.example:443") }.isFailure)
    }

    @Test
    fun shadowsocksAcceptsBase64PlainAndLegacyUserInfo() {
        val encoded =
            Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:pass/wo:rd".toByteArray())
        val sip002 = server(ShadowsocksConfigParser.parse("ss://$encoded@ss.example:8388/?plugin=#SIP").second)
        assertEquals("aes-256-gcm", sip002["method"].asString)
        assertEquals("pass/wo:rd", sip002["password"].asString)
        assertEquals(8388, sip002["port"].asInt)

        val (name, config) =
            ShadowsocksConfigParser.parse(
                "ss://2022-blake3-aes-128-gcm:YWJjZGVmZ2hpamtsbW5vcA%3D%3D@[2001:db8::1]:8388#SS%202022"
            )
        assertEquals("SS 2022", name)
        val modern = server(config)
        assertEquals("2022-blake3-aes-128-gcm", modern["method"].asString)
        assertEquals("YWJjZGVmZ2hpamtsbW5vcA==", modern["password"].asString)
        assertEquals("2001:db8::1", modern["address"].asString)

        val legacy =
            Base64.getEncoder().encodeToString("aes-128-gcm:p@ss@old.example:443".toByteArray())
        val old = server(ShadowsocksConfigParser.parse("ss://$legacy#Old").second)
        assertEquals("aes-128-gcm", old["method"].asString)
        assertEquals("p@ss", old["password"].asString)
        assertEquals("old.example", old["address"].asString)
        assertEquals(443, old["port"].asInt)
    }

    @Test
    fun shadowsocksRejectsPluginsInsteadOfDroppingThem() {
        val encoded = Base64.getUrlEncoder().encodeToString("aes-128-gcm:pw".toByteArray())
        for (link in
            listOf(
                "ss://$encoded@ss.example:8388/?plugin=obfs-local%3Bobfs%3Dhttp",
                "ss://$encoded@ss.example:8388?plugin=v2ray-plugin",
                "ss://$encoded@ss.example",
                "ss://bm9wZQ@ss.example:8388",
            )) {
            assertTrue(link, runCatching { ShadowsocksConfigParser.parse(link) }.isFailure)
        }
    }

    @Test
    fun hysteria2KeepsAuthObfsPortHoppingAndPin() {
        val (name, config) =
            Hysteria2ConfigParser.parse(
                "hysteria2://auth%3Apw@hy.example:443,20000-30000/?sni=front.example&insecure=1&pinSHA256=$pin&obfs=salamander&obfs-password=secret#HY"
            )
        assertEquals("HY", name)
        val outbound = outbound(config)
        assertEquals("hysteria", outbound["protocol"].asString)
        val settings = outbound.getAsJsonObject("settings")
        assertEquals(2, settings["version"].asInt)
        assertEquals("hy.example", settings["address"].asString)
        assertEquals(443, settings["port"].asInt)
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals("hysteria", stream["network"].asString)
        assertEquals("tls", stream["security"].asString)
        val tls = stream.getAsJsonObject("tlsSettings")
        assertEquals("front.example", tls["serverName"].asString)
        assertEquals(pin, tls["pinnedPeerCertSha256"].asString)
        assertFalse(tls.has("allowInsecure"))
        val hysteria = stream.getAsJsonObject("hysteriaSettings")
        assertEquals(2, hysteria["version"].asInt)
        assertEquals("auth:pw", hysteria["auth"].asString)
        val udp = stream.getAsJsonObject("finalmask").getAsJsonArray("udp").map { it.asJsonObject }
        assertEquals(listOf("salamander", "udphop"), udp.map { it["type"].asString })
        assertEquals("secret", udp[0].getAsJsonObject("settings")["password"].asString)
        assertEquals("intervalRemote", udp[1].getAsJsonObject("settings")["mode"].asString)
        assertEquals("443,20000-30000", udp[1].getAsJsonObject("settings")["remotePorts"].asString)
    }

    @Test
    fun hysteria2ShortSchemeDefaultsAndMport() {
        val plain = outbound(Hysteria2ConfigParser.parse("hy2://pw@hy.example").second)
        assertEquals(443, plain.getAsJsonObject("settings")["port"].asInt)
        val stream = plain.getAsJsonObject("streamSettings")
        assertEquals("hy.example", stream.getAsJsonObject("tlsSettings")["serverName"].asString)
        assertFalse(stream.has("finalmask"))
        val hop =
            stream(Hysteria2ConfigParser.parse("hy2://pw@hy.example:8443?mport=1000-2000").second)
                .getAsJsonObject("finalmask")
                .getAsJsonArray("udp")
                .single()
                .asJsonObject
        assertEquals("udphop", hop["type"].asString)
        assertEquals("1000-2000", hop.getAsJsonObject("settings")["remotePorts"].asString)
        for (link in
            listOf(
                "hy2://pw@hy.example?obfs=gfw",
                "hy2://pw@hy.example?obfs=salamander",
                "hy2://pw@hy.example:0-5",
                "hy2://pw@hy.example:443,70000",
                "hy2://pw@:443",
            )) {
            assertTrue(link, runCatching { Hysteria2ConfigParser.parse(link) }.isFailure)
        }
    }

    @Test
    fun socksReadsBase64AndPlainCredentials() {
        val encoded = Base64.getEncoder().encodeToString("user:pass".toByteArray())
        val (name, config) = SocksConfigParser.parse("socks://$encoded@s.example:1081#S")
        assertEquals("S", name)
        val outbound = outbound(config)
        assertEquals("socks", outbound["protocol"].asString)
        val settings = outbound.getAsJsonObject("settings")
        assertEquals("s.example", settings["address"].asString)
        assertEquals(1081, settings["port"].asInt)
        assertEquals("user", settings["user"].asString)
        assertEquals("pass", settings["pass"].asString)
        val plain = outbound(SocksConfigParser.parse("socks5://u%40x:p@[::1]").second)
        assertEquals("u@x", plain.getAsJsonObject("settings")["user"].asString)
        assertEquals("p", plain.getAsJsonObject("settings")["pass"].asString)
        assertEquals("::1", plain.getAsJsonObject("settings")["address"].asString)
        assertEquals(1080, plain.getAsJsonObject("settings")["port"].asInt)
        val open = outbound(SocksConfigParser.parse("socks://s.example:1080").second)
        assertFalse(open.getAsJsonObject("settings").has("user"))
    }

    @Test
    fun wireguardBuildsPeerAndInterface() {
        val (name, config) =
            WireguardConfigParser.parse(
                "wireguard://${encode(privateKey)}@[2001:db8::1]:51820?publickey=${encode(publicKey)}&address=10.0.0.2%2F32%2Cfd00%3A%3A2%2F128&mtu=1280&reserved=1%2C2%2C3&presharedkey=${encode(publicKey)}&keepalive=25#WG"
            )
        assertEquals("WG", name)
        val outbound = outbound(config)
        assertEquals("wireguard", outbound["protocol"].asString)
        assertFalse(outbound.has("streamSettings"))
        val settings = outbound.getAsJsonObject("settings")
        assertEquals(privateKey, settings["secretKey"].asString)
        assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), settings.getAsJsonArray("address").map { it.asString })
        assertEquals(1280, settings["mtu"].asInt)
        assertEquals(listOf(1, 2, 3), settings.getAsJsonArray("reserved").map { it.asInt })
        val peer = settings.getAsJsonArray("peers").single().asJsonObject
        assertEquals(publicKey, peer["publicKey"].asString)
        assertEquals(publicKey, peer["preSharedKey"].asString)
        assertEquals("[2001:db8::1]:51820", peer["endpoint"].asString)
        assertEquals(25, peer["keepAlive"].asInt)
        val defaults =
            outbound(
                    WireguardConfigParser.parse(
                            "wg://${encode(privateKey)}@wg.example?publickey=${encode(publicKey)}&address=10.0.0.2&reserved=AQID"
                        )
                        .second
                )
                .getAsJsonObject("settings")
        assertEquals("wg.example:51820", defaults.getAsJsonArray("peers").single().asJsonObject["endpoint"].asString)
        assertEquals(listOf(1, 2, 3), defaults.getAsJsonArray("reserved").map { it.asInt })
    }

    @Test
    fun wireguardRequiresKeysAndAddress() {
        for (link in
            listOf(
                "wireguard://wg.example:51820?publickey=${encode(publicKey)}&address=10.0.0.2",
                "wireguard://${encode(privateKey)}@wg.example?address=10.0.0.2",
                "wireguard://${encode(privateKey)}@wg.example?publickey=${encode(publicKey)}",
                "wireguard://${encode(privateKey)}@wg.example?publickey=${encode(publicKey)}&address=10.0.0.2&reserved=1%2C2",
                "wireguard://${encode(privateKey)}@wg.example?publickey=${encode(publicKey)}&address=10.0.0.2&mtu=big",
            )) {
            assertTrue(link, runCatching { WireguardConfigParser.parse(link) }.isFailure)
        }
    }

    private fun vmess(json: String) = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    private fun outbound(config: String) =
        JsonParser.parseString(config).asJsonObject.getAsJsonArray("outbounds").single().asJsonObject

    private fun stream(config: String): JsonObject = outbound(config).getAsJsonObject("streamSettings")

    private fun server(config: String) =
        outbound(config).getAsJsonObject("settings").getAsJsonArray("servers").single().asJsonObject
}
