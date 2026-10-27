package com.simplexray.an.feature.servers.manual

import com.google.gson.JsonParser
import com.simplexray.an.core.config.dns.DnsConfigCompiler
import com.simplexray.an.core.config.kernel.KernelConfigCompiler
import com.simplexray.an.core.config.ownership.LegacyTransportMigration
import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.kernel.model.KernelSettings
import com.simplexray.an.feature.servers.manual.compiler.ManualServerCompiler
import com.simplexray.an.feature.servers.manual.model.ManualServerDraft
import com.simplexray.an.feature.servers.manual.model.ManualValue
import com.simplexray.an.feature.servers.manual.schema.ManualSchemaRegistry
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ManualServerCompilerTest {
    private fun assets() =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first {
            File(it, "manual_protocols.json").isFile
        }

    private fun registry(): ManualSchemaRegistry =
        ManualSchemaRegistry.fromJson(
            File(assets(), "manual_protocols.json").readText(),
            File(assets(), "manual_streams.json").readText(),
        )

    private fun string(text: String) = ManualValue(text = text)

    private fun integer(text: String) = ManualValue("integer", text)

    private fun objectValue(vararg fields: Pair<String, ManualValue>) =
        ManualValue.objectValue(fields.toMap())

    private fun sample(registry: ManualSchemaRegistry, protocol: String): ManualServerDraft {
        val draft = registry.initialDraft(protocol)
        val settings =
            if (protocol == "wireguard")
                draft.settings
                    .withField(
                        "secretKey",
                        string(Base64.getEncoder().encodeToString(ByteArray(32) { 7 })),
                    )
                    .withField("noKernelTun", ManualValue("boolean", "true"))
                    .withField("address", ManualValue.arrayValue(listOf(string("10.0.0.2/32"))))
                    .withField(
                        "peers",
                        ManualValue.arrayValue(
                            listOf(
                                objectValue(
                                    "publicKey" to
                                        string(
                                            Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
                                        ),
                                    "endpoint" to string("127.0.0.1:51820"),
                                    "allowedIPs" to
                                        ManualValue.arrayValue(listOf(string("0.0.0.0/0"))),
                                )
                            )
                        ),
                    )
            else
                draft.settings.withField("address", string("127.0.0.1")).let {
                    when (protocol) {
                        "vless",
                        "vmess" ->
                            it.withField("id", string("b831381d-6324-4d53-ad4f-8cda48b30811"))
                        "trojan",
                        "shadowsocks" -> it.withField("password", string("fixture-password"))
                        else -> it
                    }
                }
        return draft.copy(settings = settings)
    }

    private fun assertAcceptedByPinnedCore(drafts: Map<String, ManualServerDraft>) {
        val executable = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)
        assumeTrue(executable?.canExecute() == true)
        val registry = registry()
        val directory = Files.createTempDirectory("manual-server-core").toFile()
        try {
            for ((name, draft) in drafts) {
                val created = ManualServerCompiler(registry).compile(draft)
                val normalized = OwnedConfig.build(created.json, "127.0.0.1", 10808)
                val configured =
                    KernelConfigCompiler.compile(
                        DnsConfigCompiler.compile(normalized, DnsSettings(), false),
                        KernelSettings(),
                    )
                val input = File(directory, "$name.json").apply { writeText(configured) }
                val log = File(directory, "$name.log")
                val process =
                    ProcessBuilder(executable!!.path, "run", "-test", "-c", input.path)
                        .redirectErrorStream(true)
                        .redirectOutput(log)
                        .apply {
                            environment()["XRAY_LOCATION_ASSET"] = directory.path
                            environment().remove("XRAY_MPH_CACHE")
                        }
                        .start()
                try {
                    assertTrue(name, process.waitFor(10, TimeUnit.SECONDS))
                    assertEquals("$name: ${log.readText()}", 0, process.exitValue())
                } finally {
                    process.destroyForcibly()
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun eightStandaloneProfilesAreAcceptedByPinnedCore() {
        val registry = registry()
        assertAcceptedByPinnedCore(registry.protocols.associate { it.id to sample(registry, it.id) })
    }

    @Test
    fun finalmaskVariantsAreAcceptedByPinnedCore() {
        val registry = registry()
        val base = sample(registry, "vless")
        fun masked(network: String, vararg masks: ManualValue) =
            base.copy(
                stream =
                    base.stream!!
                        .withField("network", string(network))
                        .withField("security", string(if (network == "hysteria") "tls" else "none"))
                        .withField(
                            "finalmask",
                            objectValue("udp" to ManualValue.arrayValue(masks.toList())),
                        )
            )
        fun mask(type: String, vararg settings: Pair<String, ManualValue>) =
            objectValue("type" to string(type), "settings" to objectValue(*settings))
        fun strings(vararg values: String) = ManualValue.arrayValue(values.map(::string))
        assertThrows(IllegalArgumentException::class.java) {
            ManualServerCompiler(registry)
                .compile(masked("kcp", mask("xicmp", "ips" to strings("icmp.example"))))
        }
        assertAcceptedByPinnedCore(
            mapOf(
                "kcp-seed-header" to
                    masked(
                        "kcp",
                        mask("mkcp-legacy", "value" to string("seed")),
                        mask("mkcp-legacy", "header" to string("wechat")),
                    ),
                "kcp-original-dns" to
                    masked(
                        "kcp",
                        mask("mkcp-legacy"),
                        mask(
                            "mkcp-legacy",
                            "header" to string("dns"),
                            "value" to string("dns.example"),
                        ),
                    ),
                "xdns" to
                    masked(
                        "kcp",
                        mask(
                            "xdns",
                            "domains" to
                                ManualValue.arrayValue(
                                    listOf(objectValue("names" to strings("t.example")))
                                ),
                            "resolvers" to
                                ManualValue.arrayValue(
                                    listOf(
                                        objectValue(
                                            "addrs" to
                                                strings("1.1.1.1", "tcp://8.8.8.8:53")
                                        )
                                    )
                                ),
                            "extraPoll" to integer("2"),
                        ),
                    ),
                "xicmp" to
                    masked(
                        "kcp",
                        mask(
                            "xicmp",
                            "ips" to strings("1.1.1.1", "2606:4700::1111"),
                            "dgram" to ManualValue("boolean", "true"),
                        ),
                    ),
            )
        )
    }

    @Test
    fun typedMapsPreserveValuesAndRejectDuplicateOrBlankNames() {
        val registry = registry()
        val base = sample(registry, "http")
        val header = objectValue("key" to string("X-Test"), "value" to string("alpha"))
        val mapped = ManualValue("map", items = listOf(header))
        val created =
            ManualServerCompiler(registry)
                .compile(base.copy(settings = base.settings.withField("headers", mapped)))
        val settings =
            JsonParser.parseString(created.json)
                .asJsonObject
                .getAsJsonArray("outbounds")[0]
                .asJsonObject
                .getAsJsonObject("settings")
        assertEquals("alpha", settings.getAsJsonObject("headers")["X-Test"].asString)
        for (rows in listOf(listOf(header, header), listOf(header.withField("key", string(""))))) {
            assertThrows(IllegalArgumentException::class.java) {
                ManualServerCompiler(registry)
                    .compile(
                        base.copy(
                            settings =
                                base.settings.withField("headers", ManualValue("map", items = rows))
                        )
                    )
            }
        }
    }

    @Test
    fun websocketEarlyDataUsesTypedFieldAndInactiveTransportsAreOmitted() {
        val registry = registry()
        val base = sample(registry, "vless")
        val stream =
            base.stream!!
                .withField("network", string("ws"))
                .withField(
                    "wsSettings",
                    objectValue(
                        "path" to string("/socket?token=fixture"),
                        "earlyData" to integer("1024"),
                    ),
                )
                .withField("grpcSettings", objectValue("serviceName" to string("inactive")))
        val created = ManualServerCompiler(registry).compile(base.copy(stream = stream))
        val compiled =
            JsonParser.parseString(created.json)
                .asJsonObject
                .getAsJsonArray("outbounds")[0]
                .asJsonObject
                .getAsJsonObject("streamSettings")
        assertEquals(
            "/socket?token=fixture&ed=1024",
            compiled.getAsJsonObject("wsSettings")["path"].asString,
        )
        assertFalse(compiled.has("grpcSettings"))
    }

    @Test
    fun numericAndProtocolErrorsStayInDraftAndCannotCompile() {
        val registry = registry()
        val base = sample(registry, "vless")
        for (port in listOf("abc", "0", "65536")) {
            val invalid = base.copy(settings = base.settings.withField("port", integer(port)))
            assertEquals(
                port,
                ManualServerDraft.decode(invalid.encode()).settings.field("port")!!.text,
            )
            assertThrows(IllegalArgumentException::class.java) {
                ManualServerCompiler(registry).compile(invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualServerCompiler(registry)
                .compile(base.copy(settings = base.settings.withField("id", string(""))))
        }
    }

    @Test
    fun protocolChangeKeepsOnlyCompatibleSharedFields() {
        val registry = registry()
        val base = sample(registry, "vless").copy(name = "Personal")
        val wireguard = registry.initialDraft("wireguard", base)
        assertEquals("Personal", wireguard.name)
        assertEquals("array", wireguard.settings.field("address")!!.type)
        val socks = registry.initialDraft("socks", base)
        assertEquals("127.0.0.1", socks.settings.field("address")!!.text)
        assertNull(socks.settings.field("id"))
    }

    @Test
    fun nestedDownloadTransportDoesNotInheritOuterSecurityDefaults() {
        val registry = registry()
        val base = sample(registry, "vless")
        val download =
            objectValue("tlsSettings" to objectValue("serverName" to string("inactive.test")))
        val stream =
            base.stream!!
                .withField("network", string("xhttp"))
                .withField("security", string("tls"))
                .withField(
                    "xhttpSettings",
                    objectValue("mode" to string("stream-up"), "downloadSettings" to download),
                )
        val output = ManualServerCompiler(registry).compile(base.copy(stream = stream))
        val nested =
            JsonParser.parseString(output.json)
                .asJsonObject
                .getAsJsonArray("outbounds")[0]
                .asJsonObject
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("xhttpSettings")
                .getAsJsonObject("downloadSettings")
        assertEquals("none", nested["security"].asString)
        assertEquals("raw", nested["network"].asString)
        assertFalse(nested.has("tlsSettings"))
    }

    @Test
    fun pemCertificateTextIsSerializedAsCertificateLines() {
        val registry = registry()
        val base = sample(registry, "vless")
        val certificate = "-----BEGIN CERTIFICATE-----\nZml4dHVyZQ==\n-----END CERTIFICATE-----"
        val stream =
            base.stream!!
                .withField("security", string("tls"))
                .withField(
                    "tlsSettings",
                    objectValue(
                        "certificates" to
                            ManualValue.arrayValue(
                                listOf(objectValue("certificate" to string(certificate)))
                            )
                    ),
                )
        val output = ManualServerCompiler(registry).compile(base.copy(stream = stream))
        val lines =
            JsonParser.parseString(output.json)
                .asJsonObject
                .getAsJsonArray("outbounds")[0]
                .asJsonObject
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("tlsSettings")
                .getAsJsonArray("certificates")[0]
                .asJsonObject
                .getAsJsonArray("certificate")
        assertEquals(certificate.lines(), lines.map { it.asString })
    }

    @Test
    fun catalogUsesCurrentEffectiveTransportFields() {
        val registry = registry()
        fun keys(schema: String) = registry.fields(schema).map { it.key }
        assertTrue(
            keys("SplitHTTPConfig").containsAll(listOf("sessionIDPlacement", "sessionIDKey"))
        )
        assertFalse("sessionPlacement" in keys("XHTTPExtra"))
        assertFalse("echForceQuery" in keys("TLSConfig"))
        assertFalse("udpHop" in keys("QuicParamsConfig"))
        assertFalse("uot" in keys("protocol.shadowsocks"))
        assertFalse("workers" in keys("protocol.wireguard"))
        assertFalse("domainStrategy" in keys("protocol.wireguard"))
        assertTrue("remoteDNS" in keys("protocol.wireguard"))
        assertTrue(keys("KCPConfig").containsAll(listOf("cwndMultiplier", "maxSendingWindow")))
        assertFalse("writeBufferSize" in keys("KCPConfig"))
    }

    @Test
    fun finalmaskCatalogMatchesPinnedCore() {
        val registry = registry()
        fun keys(schema: String) = registry.fields(schema).map { it.key }
        val udp = registry.fields("MaskUDP")
        val types = udp.single { it.key == "type" }.options
        assertEquals(
            listOf(
                "header-custom",
                "mkcp-legacy",
                "noise",
                "salamander",
                "sudoku",
                "xdns",
                "xicmp",
                "udphop",
            ),
            types,
        )
        assertEquals(
            types.toSet(),
            udp.single { it.key == "settings" }.metadata.getAsJsonObject("variants").keySet(),
        )
        assertEquals(listOf("header", "value"), keys("MkcpLegacy"))
        assertEquals(listOf("domains", "resolvers", "extraPoll"), keys("Xdns"))
        assertEquals(listOf("ips", "dgram"), keys("Xicmp"))
        assertTrue("useSystemCA" in keys("TLSConfig"))
        for (removed in
            listOf("Dns", "Aes128Gcm", "Original", "Srtp", "Utp", "Wechat", "Dtls", "Wireguard")) {
            assertTrue(removed, runCatching { registry.fields(removed) }.isFailure)
        }
    }

    @Test
    fun everySchemaReferenceResolves() {
        val roots =
            listOf("manual_protocols.json", "manual_streams.json").map {
                JsonParser.parseString(File(assets(), it).readText()).asJsonObject
            }
        val schemas = roots.flatMap { it.getAsJsonObject("schemas").keySet() }.toSet()
        val references = mutableListOf<String>()
        fun collect(element: com.google.gson.JsonElement) {
            when {
                element.isJsonObject ->
                    element.asJsonObject.entrySet().forEach { (key, value) ->
                        if (key == "ref" && value.isJsonPrimitive) references += value.asString
                        if (key == "variants" && value.isJsonObject)
                            value.asJsonObject.entrySet().forEach { (_, target) ->
                                if (target.isJsonPrimitive) references += target.asString
                            }
                        collect(value)
                    }
                element.isJsonArray -> element.asJsonArray.forEach(::collect)
            }
        }
        roots.forEach { collect(it.getAsJsonObject("schemas")) }
        assertEquals(emptyList<String>(), references.filterNot { it in schemas })
    }

    @Test
    fun removedMkcpMasksAndKcpObfuscationMigrateToMkcpLegacy() {
        fun migrate(stream: String) =
            JsonParser.parseString(
                    """{"protocol":"vless","settings":{},"streamSettings":$stream}"""
                )
                .asJsonObject
                .also(LegacyTransportMigration::apply)
                .getAsJsonObject("streamSettings")
        fun udp(stream: com.google.gson.JsonObject) =
            stream.getAsJsonObject("finalmask").getAsJsonArray("udp").map { it.toString() }
        val legacy = """{"type":"mkcp-legacy"}"""
        fun legacy(settings: String) = """{"type":"mkcp-legacy","settings":$settings}"""

        val kcp =
            migrate(
                """{"network":"kcp","kcpSettings":{"mtu":1350,"header":{"type":"wechat-video"},"seed":"s"}}"""
            )
        assertEquals(
            listOf(legacy("""{"value":"s"}"""), legacy("""{"header":"wechat"}""")),
            udp(kcp),
        )
        assertEquals("""{"mtu":1350}""", kcp["kcpSettings"].toString())
        assertEquals(
            listOf(legacy, legacy("""{"header":"dns","value":"d.example"}""")),
            udp(
                migrate(
                    """{"network":"mkcp","kcpSettings":{"header":{"type":"dns","domain":"d.example"}}}"""
                )
            ),
        )
        assertEquals(
            listOf(legacy("""{"value":"s"}"""), """{"type":"noise"}"""),
            udp(
                migrate(
                    """{"network":"kcp","kcpSettings":{"header":{"type":"none"},"seed":"s"},"finalmask":{"udp":[{"type":"noise"}]}}"""
                )
            ),
        )
        val modern =
            migrate(
                """{"network":"kcp","kcpSettings":{"seed":"old"},"finalmask":{"udp":[{"type":"mkcp-legacy","settings":{"value":"new"}}]}}"""
            )
        assertEquals(listOf(legacy("""{"value":"new"}""")), udp(modern))
        assertFalse(modern.getAsJsonObject("kcpSettings").has("seed"))
        val inactive = migrate("""{"network":"ws","kcpSettings":{"seed":"old"}}""")
        assertEquals("old", inactive.getAsJsonObject("kcpSettings")["seed"].asString)

        assertEquals(
            listOf(
                legacy("""{"value":"p"}"""),
                legacy,
                legacy("""{"header":"dns","value":"d.example"}"""),
                legacy("""{"header":"wechat"}"""),
                legacy("""{"header":"srtp"}"""),
                """{"type":"noise","settings":{"reset":"1-2"}}""",
            ),
            udp(
                migrate(
                    """{"network":"kcp","finalmask":{"udp":[
                        {"type":"mkcp-aes128gcm","settings":{"password":"p"}},
                        {"type":"mkcp-original"},
                        {"type":"header-dns","settings":{"domain":"d.example"}},
                        {"type":"header-wechat"},
                        {"type":"header-srtp","settings":{}},
                        {"type":"noise","settings":{"reset":"1-2"}}]}}"""
                )
            ),
        )

        val xdns =
            udp(
                migrate(
                    """{"network":"kcp","finalmask":{"udp":[
                        {"type":"xdns","settings":{"domain":"t.example"}},
                        {"type":"xdns","settings":{"domains":[{"name":"a.example","lenLimit":200},{"names":["b.example"]}],
                            "resolvers":[{"type":"udp","settings":{"addr":"1.1.1.1:53"}},{"type":"tcp","settings":{"addr":"8.8.8.8:53"}},{"addrs":["9.9.9.9"]}]}}]}}"""
                )
            )
        assertEquals(
            listOf(
                """{"type":"xdns","settings":{"domains":[{"names":["t.example"]}]}}""",
                """{"type":"xdns","settings":{"domains":[{"lenLimit":200,"names":["a.example"]},{"names":["b.example"]}],"resolvers":[{"addrs":["udp://1.1.1.1:53"]},{"addrs":["tcp://8.8.8.8:53"]},{"addrs":["9.9.9.9"]}]}}""",
            ),
            xdns,
        )

        assertThrows(IllegalArgumentException::class.java) {
            migrate("""{"network":"kcp","kcpSettings":{"header":{"type":"morse"}}}""")
        }
    }

    @Test
    fun legacyDraftKeepsXhttpSessionsAndNestedDownloadSettings() {
        val registry = registry()
        val base = sample(registry, "vless")
        val stream =
            base.stream!!
                .withField("network", string("xhttp"))
                .withField(
                    "xhttpSettings",
                    objectValue(
                        "mode" to string("stream-up"),
                        "sessionPlacement" to string("header"),
                        "sessionKey" to string("Old-Session"),
                        "sessionIDKey" to string("Modern-Session"),
                        "downloadSettings" to
                            objectValue(
                                "network" to string("xhttp"),
                                "xhttpSettings" to
                                    objectValue(
                                        "extra" to
                                            objectValue(
                                                "sessionPlacement" to string("query"),
                                                "sessionKey" to string("download-id"),
                                            )
                                    ),
                            ),
                    ),
                )
        val draft = base.copy(stream = stream)
        val result = ManualServerCompiler(registry).compile(draft)
        val output =
            JsonParser.parseString(result.json)
                .asJsonObject
                .getAsJsonArray("outbounds")[0]
                .asJsonObject
        val xhttp = output.getAsJsonObject("streamSettings").getAsJsonObject("xhttpSettings")
        assertEquals("header", xhttp["sessionIDPlacement"].asString)
        assertEquals("Modern-Session", xhttp["sessionIDKey"].asString)
        assertFalse(xhttp.has("sessionKey"))
        val extra =
            xhttp
                .getAsJsonObject("downloadSettings")
                .getAsJsonObject("xhttpSettings")
                .getAsJsonObject("extra")
        assertEquals("query", extra["sessionIDPlacement"].asString)
        assertEquals("download-id", extra["sessionIDKey"].asString)
        assertEquals(
            "Old-Session",
            draft.stream!!.field("xhttpSettings")!!.field("sessionKey")!!.text,
        )
    }

    @Test
    fun legacyRuntimeMigrationPreservesBuffersHopsAndModernPrecedence() {
        val outbound =
            JsonParser.parseString(
                    """{
            "protocol":"vless","settings":{},"streamSettings":{
                "network":"hysteria","security":"tls",
                "kcpSettings":{"writeBufferSize":3,"congestion":true},
                "tlsSettings":{"echForceQuery":"full"},
                "finalmask":{"udp":[{"type":"salamander","settings":{"password":"fixture"}}],
                    "quicParams":{"udpHop":{"ports":"443,8443-8444","interval":"10-20"}}}
            }
        }"""
                )
                .asJsonObject
        LegacyTransportMigration.apply(outbound)
        val stream = outbound.getAsJsonObject("streamSettings")
        val kcp = stream.getAsJsonObject("kcpSettings")
        stream.addProperty("network", "kcp")
        LegacyTransportMigration.apply(outbound)
        stream.addProperty("network", "hysteria")
        assertEquals(3 * 1048576, kcp["maxSendingWindow"].asInt)
        assertFalse(kcp.has("writeBufferSize"))
        assertFalse(kcp.has("congestion"))
        val mask = stream.getAsJsonObject("finalmask")
        assertFalse(mask.getAsJsonObject("quicParams").has("udpHop"))
        val udp = mask.getAsJsonArray("udp")
        assertEquals("salamander", udp[0].asJsonObject["type"].asString)
        val hop = udp[1].asJsonObject
        assertEquals("udphop", hop["type"].asString)
        assertEquals("intervalRemote", hop.getAsJsonObject("settings")["mode"].asString)
        assertEquals("443,8443-8444", hop.getAsJsonObject("settings")["remotePorts"].asString)
        assertEquals("10-20", hop.getAsJsonObject("settings")["interval"].asString)
        kcp.addProperty("writeBufferSize", 9)
        mask
            .getAsJsonObject("quicParams")
            .add("udpHop", JsonParser.parseString("""{"ports":"1234"}"""))
        LegacyTransportMigration.apply(outbound)
        assertEquals(3 * 1048576, kcp["maxSendingWindow"].asInt)
        assertEquals(2, udp.size())
        assertFalse(mask.getAsJsonObject("quicParams").has("udpHop"))
    }

    @Test
    fun unsupportedLegacyTransportBehaviorCannotSilentlyChange() {
        for (source in
            listOf(
                """{"protocol":"shadowsocks","settings":{"address":"example.com","method":"2022-blake3-aes-128-gcm","uot":true}}""",
                """{"protocol":"shadowsocks","settings":{"servers":[{"method":"2022-blake3-aes-128-gcm","uot":true}]}}""",
                """{"protocol":"wireguard","settings":{"domainStrategy":"ForceIPv4"}}""",
                """{"protocol":"vless","streamSettings":{"security":"tls","tlsSettings":{"echConfigList":"fixture","echForceQuery":"half"}}}""",
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                LegacyTransportMigration.apply(JsonParser.parseString(source).asJsonObject)
            }
        }
    }

    @Test
    fun manualDraftMigratesHoppingAndZeroBufferInNewUnits() {
        val registry = registry()
        val base = sample(registry, "vless")
        val stream =
            base.stream!!
                .withField("network", string("kcp"))
                .withField("kcpSettings", objectValue("writeBufferSize" to integer("0")))
                .withField(
                    "finalmask",
                    objectValue(
                        "quicParams" to
                            objectValue(
                                "udpHop" to
                                    objectValue(
                                        "ports" to string("443,8443"),
                                        "interval" to string("5-10"),
                                    )
                            )
                    ),
                )
        val output = ManualServerCompiler(registry).compile(base.copy(stream = stream))
        val compiled =
            JsonParser.parseString(output.json)
                .asJsonObject
                .getAsJsonArray("outbounds")[0]
                .asJsonObject
                .getAsJsonObject("streamSettings")
        assertEquals(524288, compiled.getAsJsonObject("kcpSettings")["maxSendingWindow"].asInt)
        val hopped =
            ManualServerCompiler(registry)
                .compile(
                    base.copy(
                        stream =
                            stream
                                .withField("network", string("hysteria"))
                                .withField("security", string("tls"))
                    )
                )
        val hopping =
            JsonParser.parseString(hopped.json)
                .asJsonObject
                .getAsJsonArray("outbounds")[0]
                .asJsonObject
                .getAsJsonObject("streamSettings")
        assertEquals(
            "udphop",
            hopping
                .getAsJsonObject("finalmask")
                .getAsJsonArray("udp")[0]
                .asJsonObject["type"]
                .asString,
        )
    }

    @Test
    fun latestKcpBoundsAndWireguardDnsAreValidatedLocally() {
        val registry = registry()
        val compiler = ManualServerCompiler(registry)
        val base = sample(registry, "vless")
        for (kcp in
            listOf(
                objectValue("mtu" to integer("20")),
                objectValue("tti" to integer("1001")),
                objectValue("cwndMultiplier" to integer("0")),
                objectValue("mtu" to integer("1400"), "maxSendingWindow" to integer("1350")),
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(
                    base.copy(
                        stream =
                            base.stream!!
                                .withField("network", string("kcp"))
                                .withField("kcpSettings", kcp)
                    )
                )
            }
        }
        val wg = sample(registry, "wireguard")
        compiler.compile(
            wg.copy(
                settings =
                    wg.settings
                        .withField("address", ManualValue.arrayValue(listOf(string("10.0.0.2/24"))))
                        .withField(
                            "remoteDNS",
                            ManualValue.arrayValue(listOf(string("10.0.0.1"), string("fd00::1"))),
                        )
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(
                wg.copy(
                    settings =
                        wg.settings.withField(
                            "remoteDNS",
                            ManualValue.arrayValue(listOf(string("dns.example"))),
                        )
                )
            )
        }
    }

    @Test
    fun inactiveLegacyFieldsAndOverriddenValuesStayIgnored() {
        val outbound =
            JsonParser.parseString(
                    """{
            "protocol":"shadowsocks",
            "settings":{"address":"127.0.0.1","method":"aes-128-gcm","uot":true,
                "servers":[{"method":"2022-blake3-aes-128-gcm","uot":true}]},
            "streamSettings":{"method":"xhttp","network":"kcp","security":"none",
                "tlsSettings":{"echConfigList":"fixture","echForceQuery":"half"},
                "kcpSettings":{"writeBufferSize":"broken"},
                "splithttpSettings":{"downloadSettings":{"security":"tls","tlsSettings":{"echConfigList":"fixture","echForceQuery":"half"}}},
                "xhttpSettings":{"downloadSettings":{"security":"tls","tlsSettings":{"echConfigList":"fixture","echForceQuery":"half"}},"extra":{"sessionKey":"legacy","sessionIDKey":"modern"}},
                "finalmask":{"udp":[{"type":"udphop","settings":{"mode":"intervalRemote","remotePorts":"443"}}],"quicParams":{"udpHop":"invalid"}}
            }
        }"""
                )
                .asJsonObject
        LegacyTransportMigration.apply(outbound)
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals("broken", stream.getAsJsonObject("kcpSettings")["writeBufferSize"].asString)
        assertEquals("half", stream.getAsJsonObject("tlsSettings")["echForceQuery"].asString)
        val extra = stream.getAsJsonObject("xhttpSettings").getAsJsonObject("extra")
        assertEquals("modern", extra["sessionIDKey"].asString)
        assertFalse(extra.has("sessionKey"))
        assertTrue(
            outbound
                .getAsJsonObject("settings")
                .getAsJsonArray("servers")[0]
                .asJsonObject["uot"]
                .asBoolean
        )
        stream.addProperty("method", "kcp")
        stream.getAsJsonObject("kcpSettings").addProperty("maxSendingWindow", 2097152)
        LegacyTransportMigration.apply(outbound)
        assertEquals(2097152, stream.getAsJsonObject("kcpSettings")["maxSendingWindow"].asInt)
        assertFalse(stream.getAsJsonObject("kcpSettings").has("writeBufferSize"))
    }
}
