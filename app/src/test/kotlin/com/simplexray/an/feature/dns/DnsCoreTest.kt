package com.simplexray.an.feature.dns

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.core.config.dns.DnsConfigCompiler
import com.simplexray.an.core.config.inbound.InboundConfigCompiler
import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.core.config.routing.RoutingCompiler
import com.simplexray.an.core.config.routing.RoutingServerTags
import com.simplexray.an.feature.dns.model.DnsQueryStrategy
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.routing.model.DomainStrategy
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlock
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.model.RuleKind
import com.simplexray.an.feature.settings.model.InboundSettings
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DnsCoreTest {
    private val executable = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)

    @Test
    fun disablingUserUdpPreservesRealDnsAndTcpWithAndWithoutFakeIp() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { target ->
                target.soTimeout = 750
                for (fakeIp in listOf(false, true)) {
                    for (udpEnabled in listOf(true, false)) {
                        val port = freePort()
                        val client =
                            fixture.client(
                                port,
                                freePort(),
                                DnsSettings(
                                    primaryDns = fixture.resolver,
                                    route = RouteTarget.DIRECT,
                                    fakeIpEnabled = fakeIp,
                                ),
                                RoutingSettings(
                                    defaultRoute = RouteTarget.DIRECT,
                                    bypassLan = true,
                                ),
                                false,
                                InboundSettings(
                                    socksPort = port,
                                    httpProxyEnabled = false,
                                    socksUdpEnabled = udpEnabled,
                                ),
                            )
                        val resolved = queryUdp(port, "target.test", 1)
                        assertEquals(
                            fakeIp,
                            InetAddress.getByAddress(resolved).hostAddress!!.startsWith("198.19."),
                        )
                        assertTrue(request(port, resolved, fixture.http.localPort))
                        connect(port, byteArrayOf(0, 0, 0, 0), 0, 3).use {
                            DatagramSocket().use { sender ->
                                val bytes = ByteArrayOutputStream()
                                DataOutputStream(bytes).use { output ->
                                    output.write(byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1))
                                    output.writeShort(target.localPort)
                                    output.writeBytes("user-udp")
                                }
                                val payload = bytes.toByteArray()
                                sender.send(DatagramPacket(payload, payload.size, udpRelay.get()))
                                val received = DatagramPacket(ByteArray(64), 64)
                                if (udpEnabled) {
                                    target.receive(received)
                                    assertEquals(
                                        "user-udp",
                                        String(received.data, 0, received.length),
                                    )
                                } else {
                                    assertThrows(SocketTimeoutException::class.java) {
                                        target.receive(received)
                                    }
                                }
                            }
                        }
                        fixture.stop(client)
                    }
                }
                assertTrue(fixture.queries.get() > 0)
            }
        }
    }

    @Test
    fun actualFakeDnsTcpUdpAndRestoredRoutesUseRealInternalResolution() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            val upstreamPort = freePort()
            val upstream =
                fixture.start(
                    "upstream",
                    """{"inbounds":[{"listen":"127.0.0.1","port":$upstreamPort,"protocol":"socks","settings":{"udp":true}}],"dns":{"servers":[{"address":"127.0.0.1","port":${fixture.dns.localPort}}]},"outbounds":[{"protocol":"freedom","settings":{"domainStrategy":"ForceIPv4"}}]}""",
                    upstreamPort,
                )
            for (target in RouteTarget.entries) {
                val port = freePort()
                val rule =
                    RoutingRule(name = "Target", values = listOf("target.test"), target = target)
                val client =
                    fixture.client(
                        port,
                        upstreamPort,
                        DnsSettings(primaryDns = fixture.resolver, route = RouteTarget.DIRECT),
                        RoutingSettings(
                            defaultRoute = RouteTarget.BLOCK,
                            bypassLan = false,
                            rules =
                                listOf(
                                    rule,
                                    rule.copy(
                                        id = "later",
                                        target =
                                            if (target == RouteTarget.BLOCK) RouteTarget.DIRECT
                                            else RouteTarget.BLOCK,
                                    ),
                                ),
                        ),
                        true,
                    )
                val a = queryTcp(port, "target.test", 1)
                val aaaa = queryUdp(port, "target.test", 28)
                assertEquals(
                    "198.19",
                    InetAddress.getByAddress(a).hostAddress!!.split('.').take(2).joinToString("."),
                )
                assertTrue(InetAddress.getByAddress(aaaa).hostAddress!!.startsWith("fd00:198:19:"))
                assertEquals(target != RouteTarget.BLOCK, request(port, a, fixture.http.localPort))
                assertEquals(
                    target != RouteTarget.BLOCK,
                    request(port, aaaa, fixture.http.localPort),
                )
                assertFalse(
                    request(
                        port,
                        InetAddress.getByName("198.19.255.254").address,
                        fixture.http.localPort,
                        false,
                    )
                )
                assertFalse(
                    request(
                        port,
                        InetAddress.getByName("fd00:198:19::ffff").address,
                        fixture.http.localPort,
                        false,
                    )
                )
                fixture.stop(client)
            }
            fixture.stop(upstream)
            val directPort = freePort()
            fixture.client(
                directPort,
                upstreamPort,
                DnsSettings(primaryDns = fixture.resolver, route = RouteTarget.DIRECT),
                RoutingSettings(defaultRoute = RouteTarget.DIRECT, bypassLan = false),
                false,
            )
            val fake = queryTcp(directPort, "target.test", 1)
            assertTrue(request(directPort, fake, fixture.http.localPort))
            assertTrue("Direct outbound must query real built-in DNS", fixture.queries.get() > 0)
            val proxyPort = freePort()
            fixture.client(
                proxyPort,
                upstreamPort,
                DnsSettings(primaryDns = fixture.resolver, route = RouteTarget.DIRECT),
                RoutingSettings(defaultRoute = RouteTarget.PROXY, bypassLan = false),
                false,
            )
            assertFalse(
                request(proxyPort, queryTcp(proxyPort, "target.test", 1), fixture.http.localPort)
            )
        }
    }

    @Test
    fun restoredFakeIpHonorsIpBlockBeforeConflictingDirectDefault() {
        val block =
            RoutingRule(
                name = "Block real IP",
                kind = RuleKind.IP,
                values = listOf("127.0.0.1/32"),
                target = RouteTarget.BLOCK,
            )
        assertFakeIpRoute(
            RoutingSettings(
                defaultRoute = RouteTarget.DIRECT,
                bypassLan = false,
                domainStrategy = DomainStrategy.AS_IS,
                rules = listOf(block, block.copy(id = "later", target = RouteTarget.DIRECT)),
            ),
            false,
        )
    }

    @Test
    fun restoredFakeIpHonorsIpDirectBeforeConflictingBlockDefault() {
        val direct =
            RoutingRule(
                name = "Direct real IP",
                kind = RuleKind.IP,
                values = listOf("127.0.0.1/32"),
                target = RouteTarget.DIRECT,
            )
        assertFakeIpRoute(
            RoutingSettings(
                defaultRoute = RouteTarget.BLOCK,
                bypassLan = false,
                domainStrategy = DomainStrategy.AS_IS,
                rules = listOf(direct, direct.copy(id = "later", target = RouteTarget.BLOCK)),
            ),
            true,
        )
    }

    @Test
    fun restoredFakeIpHonorsLanBypassBeforeDomainAndDefaultBlock() {
        val block =
            RoutingRule(
                name = "Block domain",
                values = listOf("target.test"),
                target = RouteTarget.BLOCK,
            )
        assertFakeIpRoute(
            RoutingSettings(
                defaultRoute = RouteTarget.BLOCK,
                bypassLan = true,
                domainStrategy = DomainStrategy.AS_IS,
                rules = listOf(block),
            ),
            true,
        )
    }

    @Test
    fun restoredFakeIpKeepsEarlierDomainRuleAheadOfIpRule() {
        val domain =
            RoutingRule(
                name = "Block domain",
                values = listOf("target.test"),
                target = RouteTarget.BLOCK,
            )
        val ip =
            RoutingRule(
                name = "Direct IP",
                kind = RuleKind.IP,
                values = listOf("127.0.0.1/32"),
                target = RouteTarget.DIRECT,
            )
        assertFakeIpRoute(
            RoutingSettings(
                defaultRoute = RouteTarget.DIRECT,
                bypassLan = false,
                domainStrategy = DomainStrategy.AS_IS,
                rules = listOf(domain, ip),
            ),
            false,
        )
    }

    private fun assertFakeIpRoute(settings: RoutingSettings, reachable: Boolean) {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            val port = freePort()
            fixture.client(
                port,
                freePort(),
                DnsSettings(
                    fakeIpEnabled = true,
                    primaryDns = fixture.resolver,
                    route = RouteTarget.DIRECT,
                ),
                settings,
                false,
            )
            val fake = queryTcp(port, "target.test", 1)
            assertTrue(InetAddress.getByAddress(fake).hostAddress!!.startsWith("198.19."))
            assertEquals(
                "Restored fake domain must evaluate configured IP/LAN rules in order",
                reachable,
                request(port, fake, fixture.http.localPort),
            )
        }
    }

    @Test
    fun realDnsModeFallbackCacheAndProtectedInterceptionWork() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            val port = freePort()
            fixture.client(
                port,
                freePort(),
                DnsSettings(
                    fakeIpEnabled = false,
                    primaryDns = "127.0.0.1:${freePort()}",
                    fallbackEnabled = true,
                    fallbackDns = fixture.resolver,
                    route = RouteTarget.DIRECT,
                    queryStrategy = DnsQueryStrategy.IPV4,
                    cacheEnabled = false,
                ),
                RoutingSettings(
                    defaultRoute = RouteTarget.BLOCK,
                    rules =
                        listOf(
                            RoutingRule(
                                name = "Block DNS IP",
                                kind = RuleKind.IP,
                                values = listOf("0.0.0.0/0"),
                                target = RouteTarget.BLOCK,
                            )
                        ),
                ),
                false,
            )
            assertArrayEquals(
                InetAddress.getByName("127.0.0.1").address,
                queryTcp(port, "target.test", 1, 8000),
            )
            assertArrayEquals(
                InetAddress.getByName("127.0.0.1").address,
                queryUdp(port, "target.test", 1, 8000),
            )
            assertTrue(fixture.queries.get() >= 2)
            fixture.assertGuarded(port, "198.19.255.254")
            fixture.assertGuarded(port, "fd00:198:19::ffff")
            assertFalse(
                request(
                    port,
                    InetAddress.getByName("198.19.255.254").address,
                    fixture.http.localPort,
                )
            )
            assertFalse(
                request(
                    port,
                    InetAddress.getByName("fd00:198:19::ffff").address,
                    fixture.http.localPort,
                )
            )
        }
    }

    @Test
    fun dnsProxyTransportRequiresTheConfiguredUpstream() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            val upstreamPort = freePort()
            val upstream =
                fixture.start(
                    "dns-upstream",
                    """{"inbounds":[{"listen":"127.0.0.1","port":$upstreamPort,"protocol":"socks","settings":{"udp":true}}],"outbounds":[{"protocol":"freedom"}]}""",
                    upstreamPort,
                )
            val port = freePort()
            fixture.client(
                port,
                upstreamPort,
                DnsSettings(
                    fakeIpEnabled = false,
                    primaryDns = fixture.resolver,
                    route = RouteTarget.PROXY,
                    cacheEnabled = false,
                ),
                RoutingSettings(defaultRoute = RouteTarget.BLOCK),
                false,
            )
            assertArrayEquals(
                InetAddress.getByName("127.0.0.1").address,
                queryTcp(port, "first.test", 1, 5000),
            )
            fixture.stop(upstream)
            assertTrue(runCatching { queryTcp(port, "second.test", 1, 5000) }.isFailure)
            assertEquals(1, fixture.queries.get())
        }
    }

    @Test
    fun fakePoolGuardsPrecedeLanAndBroadIpRulesAndStaleDomainsCanBeRecovered() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            val port = freePort()
            fixture.client(
                port,
                freePort(),
                DnsSettings(primaryDns = fixture.resolver, route = RouteTarget.DIRECT),
                RoutingSettings(
                    defaultRoute = RouteTarget.DIRECT,
                    bypassLan = true,
                    rules =
                        listOf(
                            RoutingRule(
                                name = "All",
                                kind = RuleKind.IP,
                                values = listOf("0.0.0.0/0", "::/0"),
                                target = RouteTarget.DIRECT,
                            )
                        ),
                ),
                true,
            )
            for (ip in listOf("198.19.255.254", "fd00:198:19::ffff")) {
                fixture.assertGuarded(port, ip)
                assertFalse(
                    request(port, InetAddress.getByName(ip).address, fixture.http.localPort, false)
                )
                assertTrue(
                    "Recoverable HTTP hostname must restore a real destination",
                    request(port, InetAddress.getByName(ip).address, fixture.http.localPort),
                )
            }
        }
    }

    @Test
    fun dohHostnameBootstrapReachesPinnedIpThroughBothDnsRoutes() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            val upstreamPort = freePort()
            fixture.start(
                "bootstrap-upstream",
                """{"inbounds":[{"listen":"127.0.0.1","port":$upstreamPort,"protocol":"socks","settings":{"udp":true}}],"outbounds":[{"protocol":"freedom"}]}""",
                upstreamPort,
            )
            for (route in listOf(RouteTarget.DIRECT, RouteTarget.PROXY)) {
                ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { tls ->
                    tls.soTimeout = 6000
                    val port = freePort()
                    val client =
                        fixture.client(
                            port,
                            upstreamPort,
                            DnsSettings(
                                fakeIpEnabled = false,
                                primaryDns = "https://bootstrap.invalid:${tls.localPort}/dns-query",
                                primaryBootstrap = "127.0.0.1",
                                route = route,
                            ),
                            RoutingSettings(defaultRoute = RouteTarget.BLOCK),
                            false,
                        )
                    val query =
                        thread(isDaemon = true) {
                            runCatching { queryTcp(port, "target.test", 1, 4500) }
                        }
                    tls.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream())
                        assertEquals(22, input.readUnsignedByte())
                        input.readUnsignedShort()
                        val hello = ByteArray(input.readUnsignedShort()).also(input::readFully)
                        assertTrue(
                            "TLS must retain the DoH hostname while dialing bootstrap IP",
                            String(hello, Charsets.ISO_8859_1).contains("bootstrap.invalid"),
                        )
                    }
                    fixture.stop(client)
                    query.join(5000)
                    assertFalse(query.isAlive)
                }
            }
            assertEquals(
                "Pinned DoH endpoint needs no recursive hostname query",
                0,
                fixture.queries.get(),
            )
        }
    }

    @Test
    fun splitDnsUsesSeparateTransportsWithAndWithoutFakeIp() {
        assumeTrue(executable?.canExecute() == true)
        for (fakeIp in listOf(false, true)) {
            Fixture().use { main ->
                Fixture().use { direct ->
                    val upstream = main.upstream()
                    val port = freePort()
                    main.client(
                        port,
                        upstream,
                        splitSettings(main, direct, fakeIp),
                        splitRouting(RouteTarget.PROXY, RouteTarget.DIRECT),
                        false,
                        split = true,
                    )
                    val directAddress = queryTcp(port, "target.test", 1)
                    assertEquals(fakeIp, isFake(directAddress))
                    assertTrue(request(port, directAddress, main.http.localPort))
                    assertTrue(
                        "Direct domain must query the direct resolver",
                        direct.queries.get() > 0,
                    )
                    assertEquals("Direct lookup must not query main DNS", 0, main.queries.get())
                    val mainAddress = queryUdp(port, "proxy.test", 1)
                    assertEquals(fakeIp, isFake(mainAddress))
                    assertTrue(
                        request(port, mainAddress, main.http.localPort, domain = "proxy.test")
                    )
                    assertTrue("Proxy domain must query main DNS", main.queries.get() > 0)
                    main.assertAccessContains(
                        "split-upstream",
                        "udp:127.0.0.1:${main.dns.localPort}",
                    )
                    assertFalse(
                        "Direct DNS must not traverse the proxy",
                        main
                            .access("split-upstream")
                            .contains("udp:127.0.0.1:${direct.dns.localPort}"),
                    )
                }
            }
        }
    }

    @Test
    fun splitDnsKeepsEarlierDomainRuleAheadOfOverlappingFullRule() {
        assumeTrue(executable?.canExecute() == true)
        for (fakeIp in listOf(false, true)) {
            for (first in listOf(RouteTarget.PROXY, RouteTarget.DIRECT)) {
                Fixture().use { main ->
                    Fixture().use { direct ->
                        val second =
                            if (first == RouteTarget.DIRECT) RouteTarget.PROXY
                            else RouteTarget.DIRECT
                        val port = freePort()
                        val routing =
                            splitRouting(RouteTarget.BLOCK, first)
                                .copy(
                                    rules =
                                        listOf(
                                            resolutionTrigger(),
                                            RoutingRule(
                                                name = "Earlier suffix",
                                                kind = RuleKind.DOMAIN,
                                                values = listOf("test"),
                                                target = first,
                                            ),
                                            RoutingRule(
                                                name = "Later exact",
                                                kind = RuleKind.FULL_DOMAIN,
                                                values = listOf("target.test"),
                                                target = second,
                                            ),
                                        )
                                )
                        main.client(
                            port,
                            main.upstream(),
                            splitSettings(main, direct, fakeIp),
                            routing,
                            false,
                            split = true,
                        )
                        val address = queryTcp(port, "target.test", 1)
                        assertEquals(fakeIp, isFake(address))
                        assertTrue(request(port, address, main.http.localPort))
                        assertEquals(
                            "Only the first matching DNS group may answer",
                            first == RouteTarget.DIRECT,
                            direct.queries.get() > 0,
                        )
                        assertEquals(
                            "Later conflicting DNS group must stay unused",
                            first == RouteTarget.PROXY,
                            main.queries.get() > 0,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun splitDirectLookupWorksWithOfflineProxy() {
        assumeTrue(executable?.canExecute() == true)
        for (fakeIp in listOf(false, true)) {
            Fixture().use { main ->
                Fixture().use { direct ->
                    val port = freePort()
                    main.client(
                        port,
                        freePort(),
                        splitSettings(main, direct, fakeIp),
                        splitRouting(RouteTarget.BLOCK, RouteTarget.DIRECT),
                        false,
                        split = true,
                    )
                    val address = queryUdp(port, "target.test", 1)
                    assertEquals(fakeIp, isFake(address))
                    assertTrue(
                        "Direct domain must remain reachable without the proxy",
                        request(port, address, main.http.localPort),
                    )
                    assertTrue(direct.queries.get() > 0)
                    assertEquals(0, main.queries.get())
                }
            }
        }
    }

    @Test
    fun splitDirectNxDomainNeverFallsBackToProxyDns() {
        assumeTrue(executable?.canExecute() == true)
        for (fakeIp in listOf(false, true)) {
            Fixture().use { main ->
                Fixture(responseCode = 3).use { direct ->
                    val port = freePort()
                    val client =
                        main.client(
                            port,
                            main.upstream(),
                            splitSettings(main, direct, fakeIp)
                                .copy(fallbackEnabled = true, fallbackDns = main.resolver),
                            splitRouting(RouteTarget.PROXY, RouteTarget.DIRECT),
                            false,
                            split = true,
                        )
                    if (fakeIp) {
                        val address = queryTcp(port, "target.test", 1)
                        assertTrue(isFake(address))
                        assertFalse(request(port, address, main.http.localPort))
                    } else {
                        assertTrue(runCatching { queryTcp(port, "target.test", 1, 5000) }.isFailure)
                    }
                    main.stop(client)
                    assertTrue(
                        "The selected direct resolver must receive the query",
                        direct.queries.get() > 0,
                    )
                    assertEquals(
                        "NXDOMAIN must not leak into primary or fallback DNS",
                        0,
                        main.queries.get(),
                    )
                    assertFalse(
                        main
                            .access("split-upstream")
                            .contains("udp:127.0.0.1:${main.dns.localPort}")
                    )
                    assertFalse(
                        main
                            .access("split-upstream")
                            .contains("udp:127.0.0.1:${direct.dns.localPort}")
                    )
                }
            }
        }
    }

    @Test
    fun splitDefaultDirectUsesDirectDnsForUnmatchedDomains() {
        assumeTrue(executable?.canExecute() == true)
        for (fakeIp in listOf(false, true)) {
            Fixture().use { main ->
                Fixture().use { direct ->
                    val port = freePort()
                    main.client(
                        port,
                        freePort(),
                        splitSettings(main, direct, fakeIp),
                        splitRouting(RouteTarget.DIRECT, RouteTarget.PROXY),
                        false,
                        split = true,
                    )
                    val address = queryTcp(port, "unmatched.test", 1)
                    assertEquals(fakeIp, isFake(address))
                    assertTrue(
                        request(port, address, main.http.localPort, domain = "unmatched.test")
                    )
                    assertTrue("Default DIRECT must use direct DNS", direct.queries.get() > 0)
                    assertEquals(
                        "Unmatched direct domain must not require main DNS",
                        0,
                        main.queries.get(),
                    )
                }
            }
        }
    }

    @Test
    fun splitGeoSiteSelectsDirectDnsForIpv6WithAndWithoutFakeIp() {
        assumeTrue(executable?.canExecute() == true)
        for (fakeIp in listOf(false, true)) {
            repeat(2) {
                Fixture().use { main ->
                    Fixture().use { direct ->
                        File(main.dir, "geosite.dat")
                            .writeBytes(
                                byteArrayOf(10, 23, 10, 4, 84, 69, 83, 84, 18, 15, 8, 3, 18, 11) +
                                    "target.test".toByteArray()
                            )
                        val routing = splitRouting(RouteTarget.PROXY, RouteTarget.PROXY)
                        val geo =
                            RoutingRule(
                                name = "Direct category",
                                kind = RuleKind.GEOSITE,
                                values = listOf("test"),
                                target = RouteTarget.DIRECT,
                            )
                        val port = freePort()
                        main.client(
                            port,
                            main.upstream(),
                            splitSettings(main, direct, fakeIp)
                                .copy(queryStrategy = DnsQueryStrategy.IPV6),
                            routing.copy(
                                rules = listOf(resolutionTrigger(), geo) + routing.rules.drop(1)
                            ),
                            true,
                            split = true,
                        )
                        val address = queryUdp(port, "target.test", 28)
                        assertEquals(16, address.size)
                        assertEquals(fakeIp, isFake(address))
                        assertTrue(request(port, address, main.http.localPort))
                        assertTrue(
                            "GeoSite must select direct DNS for real IPv6 resolution",
                            direct.queries.get() > 0,
                        )
                        assertEquals(
                            "Later full-domain proxy rule must not capture the lookup",
                            0,
                            main.queries.get(),
                        )
                        val mainAddress = queryTcp(port, "proxy.test", 28)
                        assertEquals(16, mainAddress.size)
                        assertEquals(fakeIp, isFake(mainAddress))
                        assertTrue(
                            request(port, mainAddress, main.http.localPort, domain = "proxy.test")
                        )
                        assertTrue(
                            "Unmatched IPv6 lookup must use main DNS",
                            main.queries.get() > 0,
                        )
                        main.assertAccessContains(
                            "split-upstream",
                            "udp:127.0.0.1:${main.dns.localPort}",
                        )
                        assertFalse(
                            main
                                .access("split-upstream")
                                .contains("udp:127.0.0.1:${direct.dns.localPort}")
                        )
                    }
                }
            }
        }
    }

    @Test
    fun splitProxyGroupFallsBackAfterPrimaryNxDomain() {
        assumeTrue(executable?.canExecute() == true)
        for (fakeIp in listOf(false, true)) {
            Fixture(responseCode = 3).use { main ->
                Fixture().use { fallback ->
                    Fixture().use { direct ->
                        val port = freePort()
                        main.client(
                            port,
                            main.upstream(),
                            splitSettings(main, direct, fakeIp)
                                .copy(fallbackEnabled = true, fallbackDns = fallback.resolver),
                            splitRouting(RouteTarget.DIRECT, RouteTarget.PROXY),
                            false,
                            split = true,
                        )
                        val address = queryTcp(port, "target.test", 1, 5000)
                        assertEquals(fakeIp, isFake(address))
                        assertTrue(request(port, address, main.http.localPort))
                        assertTrue("Primary DNS must be tried first", main.queries.get() > 0)
                        assertTrue(
                            "Fallback must answer inside the matched proxy group",
                            fallback.queries.get() > 0,
                        )
                        assertEquals(
                            "Proxy DNS failure must not fall through to direct default",
                            0,
                            direct.queries.get(),
                        )
                        main.assertAccessContains(
                            "split-upstream",
                            "udp:127.0.0.1:${main.dns.localPort}",
                        )
                        main.assertAccessContains(
                            "split-upstream",
                            "udp:127.0.0.1:${fallback.dns.localPort}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun customServerDnsAndFallbackUseTheirSelectedExitDespiteGlobalDirect() {
        assumeTrue(executable?.canExecute() == true)
        Fixture(responseCode = 3).use { primary ->
            Fixture().use { fallback ->
                Fixture().use { direct ->
                    val alpha = primary.upstream("alpha")
                    val beta = primary.upstream("beta")
                    val port = freePort()
                    val client =
                        primary.client(
                            port,
                            primary.upstream(),
                            splitSettings(primary, direct, false)
                                .copy(
                                    route = RouteTarget.DIRECT,
                                    fallbackEnabled = true,
                                    fallbackDns = fallback.resolver,
                                ),
                            serverRouting(),
                            false,
                            split = true,
                            serverUpstreams = mapOf("alpha" to alpha, "beta" to beta),
                        )
                    assertEquals(
                        "127.0.0.1",
                        InetAddress.getByAddress(queryTcp(port, "target.test", 1, 5000)).hostAddress,
                    )
                    primary.assertAccessContains("alpha", "udp:${primary.resolver}")
                    primary.assertAccessContains("alpha", "udp:${fallback.resolver}")
                    assertFalse(primary.access("beta").contains("udp:"))
                    assertEquals(
                        "127.0.0.1",
                        InetAddress.getByAddress(queryTcp(port, "beta.test", 1, 5000)).hostAddress,
                    )
                    primary.assertAccessContains("beta", "udp:${primary.resolver}")
                    primary.assertAccessContains("beta", "udp:${fallback.resolver}")
                    primary.stop(client)
                    assertEquals(2, primary.queries.get())
                    assertEquals(2, fallback.queries.get())
                    assertEquals(0, direct.queries.get())
                    assertFalse(primary.access("split-upstream").contains("udp:"))
                }
            }
        }
    }

    @Test
    fun customServerDnsFailureCannotFallThroughToOverlappingServerOrDirectDefault() {
        assumeTrue(executable?.canExecute() == true)
        Fixture(responseCode = 3).use { primary ->
            Fixture(responseCode = 3).use { fallback ->
                Fixture().use { direct ->
                    val alpha = primary.upstream("alpha")
                    val beta = primary.upstream("beta")
                    val port = freePort()
                    val client =
                        primary.client(
                            port,
                            primary.upstream(),
                            splitSettings(primary, direct, false)
                                .copy(
                                    route = RouteTarget.DIRECT,
                                    fallbackEnabled = true,
                                    fallbackDns = fallback.resolver,
                                ),
                            serverRouting(),
                            false,
                            split = true,
                            serverUpstreams = mapOf("alpha" to alpha, "beta" to beta),
                        )
                    assertTrue(runCatching { queryTcp(port, "target.test", 1, 5000) }.isFailure)
                    primary.assertAccessContains("alpha", "udp:${primary.resolver}")
                    primary.assertAccessContains("alpha", "udp:${fallback.resolver}")
                    primary.stop(client)
                    assertEquals(1, primary.queries.get())
                    assertEquals(1, fallback.queries.get())
                    assertEquals(0, direct.queries.get())
                    assertFalse(primary.access("beta").contains("udp:"))
                    assertFalse(primary.access("split-upstream").contains("udp:"))
                }
            }
        }
    }

    private fun serverRouting(): RoutingSettings {
        val blocks =
            listOf(
                RoutingBlock(RouteTarget.PROXY, "suffix:target.test", "alpha"),
                RoutingBlock(RouteTarget.PROXY, "full:target.test\nfull:beta.test", "beta"),
                RoutingBlock(RouteTarget.PROXY),
                RoutingBlock(RouteTarget.DIRECT),
                RoutingBlock(RouteTarget.BLOCK),
            )
        return RoutingSettings(
            defaultRoute = RouteTarget.DIRECT,
            bypassLan = false,
            blocks = blocks,
            rules = blocks.flatMap(RoutingBlocks::parse),
        )
    }

    private fun splitSettings(main: Fixture, direct: Fixture, fakeIp: Boolean) =
        DnsSettings(
            primaryDns = main.resolver,
            directDns = direct.resolver,
            route = RouteTarget.PROXY,
            fakeIpEnabled = fakeIp,
            cacheEnabled = false,
            queryStrategy = DnsQueryStrategy.IPV4,
        )

    private fun resolutionTrigger() =
        RoutingRule(
            name = "Resolve before matching",
            kind = RuleKind.IP,
            values = listOf("192.0.2.0/24"),
            target = RouteTarget.BLOCK,
        )

    private fun splitRouting(default: RouteTarget, target: RouteTarget) =
        RoutingSettings(
            defaultRoute = default,
            bypassLan = false,
            domainStrategy = DomainStrategy.IP_ON_DEMAND,
            rules =
                listOf(
                    resolutionTrigger(),
                    RoutingRule(
                        name = "Exact target",
                        kind = RuleKind.FULL_DOMAIN,
                        values = listOf("target.test"),
                        target = target,
                    ),
                ),
        )

    private fun isFake(address: ByteArray): Boolean {
        val value = InetAddress.getByAddress(address).hostAddress!!
        return value.startsWith("198.19.") || value.startsWith("fd00:198:19:")
    }

    @Test
    fun categoryRulesFailPreflightWhenRequiredAssetsAreMissing() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            for (kind in listOf(RuleKind.GEOIP, RuleKind.GEOSITE)) {
                val owned = OwnedConfig.build(source(freePort()), "127.0.0.1", freePort())
                val config =
                    RoutingCompiler.compile(
                        DnsConfigCompiler.compile(
                            owned,
                            DnsSettings(primaryDns = fixture.resolver),
                            false,
                        ),
                        RoutingSettings(
                            rules =
                                listOf(
                                    RoutingRule(name = "Geo", kind = kind, values = listOf("ru"))
                                )
                        ),
                    )
                val file = File(fixture.dir, "geo.json").apply { writeText(config) }
                val child =
                    ProcessBuilder(executable!!.path, "run", "-test", "-c", file.path)
                        .redirectErrorStream(true)
                        .apply { environment()["XRAY_LOCATION_ASSET"] = fixture.dir.path }
                        .start()
                val output = child.inputStream.bufferedReader().readText()
                assertTrue(child.waitFor(5, TimeUnit.SECONDS))
                assertNotEquals(output, 0, child.exitValue())
                assertTrue(
                    output,
                    output.contains(if (kind == RuleKind.GEOIP) "geoip.dat" else "geosite.dat"),
                )
            }
        }
    }

    private fun source(upstream: Int) =
        """{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":$upstream}]}}"""

    private fun freePort(): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        repeat(100) {
            val port =
                runCatching {
                        ServerSocket(0, 1, loopback).use { tcp ->
                            DatagramSocket(tcp.localPort, loopback).use { tcp.localPort }
                        }
                    }
                    .getOrNull()
            if (port != null) return port
        }
        error("Unable to reserve a free TCP and UDP port")
    }

    private fun connect(
        port: Int,
        address: ByteArray,
        destinationPort: Int,
        command: Int = 1,
        timeout: Int = 2500,
    ): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), timeout)
            socket.soTimeout = timeout
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            output.write(byteArrayOf(5, 1, 0))
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
            output.write(byteArrayOf(5, command.toByte(), 0, if (address.size == 4) 1 else 4))
            output.write(address)
            output.writeShort(destinationPort)
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
            input.readUnsignedByte()
            val length =
                when (input.readUnsignedByte()) {
                    1 -> 4
                    4 -> 16
                    else -> input.readUnsignedByte()
                }
            val bound = ByteArray(length).also(input::readFully)
            val boundPort = input.readUnsignedShort()
            if (command == 3)
                udpRelay.set(InetSocketAddress(InetAddress.getByAddress(bound), boundPort))
            return socket
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    private val udpRelay = ThreadLocal<InetSocketAddress>()

    private fun question(domain: String, type: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeShort(42)
            output.writeShort(0x100)
            output.writeShort(1)
            repeat(3) { output.writeShort(0) }
            domain.split('.').forEach {
                output.writeByte(it.length)
                output.writeBytes(it)
            }
            output.writeByte(0)
            output.writeShort(type)
            output.writeShort(1)
        }
        return bytes.toByteArray()
    }

    private fun queryTcp(port: Int, domain: String, type: Int, timeout: Int = 2500): ByteArray =
        connect(port, byteArrayOf(9, 9, 9, 9), 53, timeout = timeout).use { socket ->
            val payload = question(domain, type)
            DataOutputStream(socket.getOutputStream()).apply {
                writeShort(payload.size)
                write(payload)
            }
            val input = DataInputStream(socket.getInputStream())
            answer(ByteArray(input.readUnsignedShort()).also(input::readFully), type)
        }

    private fun queryUdp(port: Int, domain: String, type: Int, timeout: Int = 2500): ByteArray =
        connect(port, byteArrayOf(0, 0, 0, 0), 0, 3, timeout).use {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeout
                val payload = byteArrayOf(0, 0, 0, 1, 9, 9, 9, 9, 0, 53) + question(domain, type)
                socket.send(DatagramPacket(payload, payload.size, udpRelay.get()))
                val response = DatagramPacket(ByteArray(4096), 4096)
                socket.receive(response)
                val offset =
                    when (response.data[3].toInt()) {
                        1 -> 10
                        4 -> 22
                        else -> 7 + response.data[4].toInt()
                    }
                answer(response.data.copyOfRange(offset, response.length), type)
            }
        }

    private fun answer(bytes: ByteArray, type: Int): ByteArray {
        val input = DataInputStream(bytes.inputStream())
        input.readUnsignedShort()
        val flags = input.readUnsignedShort()
        check(flags and 15 == 0) { "DNS response flags $flags" }
        val questions = input.readUnsignedShort()
        val count = input.readUnsignedShort()
        input.skipBytes(4)
        fun name() {
            while (true) {
                val length = input.readUnsignedByte()
                if (length == 0) return
                if (length and 0xc0 == 0xc0) {
                    input.readUnsignedByte()
                    return
                }
                input.skipBytes(length)
            }
        }
        repeat(questions) {
            name()
            input.skipBytes(4)
        }
        repeat(count) {
            name()
            val recordType = input.readUnsignedShort()
            input.skipBytes(6)
            val result = ByteArray(input.readUnsignedShort()).also(input::readFully)
            if (recordType == type) return result
        }
        error("DNS answer did not contain type $type")
    }

    private fun request(
        port: Int,
        address: ByteArray,
        targetPort: Int,
        recoverable: Boolean = true,
        domain: String = "target.test",
    ): Boolean =
        runCatching {
                connect(port, address, targetPort).use { socket ->
                    val payload =
                        if (recoverable)
                            "GET / HTTP/1.1\r\nHost: $domain\r\nConnection: close\r\n\r\n"
                        else "unrecoverable\r\n\r\n"
                    socket.getOutputStream().write(payload.toByteArray())
                    socket.getInputStream().bufferedReader().readLine()?.contains("200 OK") == true
                }
            }
            .getOrDefault(false)

    private inner class Fixture(private val responseCode: Int = 0) : AutoCloseable {
        val dir = Files.createTempDirectory("dns-core").toFile()
        val dns = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val resolver = "127.0.0.1:${dns.localPort}"
        val queries = AtomicInteger()
        val http = ServerSocket(0, 20, InetAddress.getByName("::"))
        private val children = mutableListOf<Process>()
        private val dnsThread =
            thread(isDaemon = true) {
                while (!dns.isClosed) runCatching {
                    val packet = DatagramPacket(ByteArray(4096), 4096)
                    dns.receive(packet)
                    queries.incrementAndGet()
                    val query = packet.data.copyOf(packet.length)
                    var end = 12
                    while (query[end].toInt() != 0) end += 1 + (query[end].toInt() and 255)
                    end++
                    val type =
                        ((query[end].toInt() and 255) shl 8) or (query[end + 1].toInt() and 255)
                    val ip = InetAddress.getByName(if (type == 28) "::1" else "127.0.0.1").address
                    val bytes = ByteArrayOutputStream()
                    DataOutputStream(bytes).use { output ->
                        output.write(query, 0, 2)
                        output.writeShort(0x8180 or responseCode)
                        output.writeShort(1)
                        output.writeShort(if (responseCode == 0) 1 else 0)
                        output.writeInt(0)
                        output.write(query, 12, end + 4 - 12)
                        if (responseCode == 0) {
                            output.writeShort(0xc00c)
                            output.writeShort(type)
                            output.writeShort(1)
                            output.writeInt(60)
                            output.writeShort(ip.size)
                            output.write(ip)
                        }
                    }
                    val response = bytes.toByteArray()
                    dns.send(DatagramPacket(response, response.size, packet.socketAddress))
                }
            }
        private val httpThread =
            thread(isDaemon = true) {
                while (!http.isClosed) runCatching {
                    http.accept().use { socket ->
                        socket.soTimeout = 2000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) {}
                        socket
                            .getOutputStream()
                            .write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                                    .toByteArray()
                            )
                    }
                }
            }

        fun upstream(name: String = "split-upstream"): Int {
            val port = freePort()
            start(
                name,
                """{"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"socks","settings":{"udp":true}}],"dns":{"hosts":{"target.test":"127.0.0.1","proxy.test":"127.0.0.1"}},"outbounds":[{"protocol":"freedom","settings":{"domainStrategy":"ForceIPv4"}}]}""",
                port,
            )
            return port
        }

        fun access(name: String): String =
            File(dir, "$name.access").let { if (it.exists()) it.readText() else "" }

        fun assertAccessContains(name: String, target: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (!access(name).contains(target) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(
                "Missing $target in proxy access log: ${access(name)}",
                access(name).contains(target),
            )
        }

        fun assertGuarded(port: Int, address: String) {
            val access = File(dir, "client-$port.access")
            fun matches() =
                if (access.exists()) access.readLines().count { it.contains("__sx_fake_block") }
                else 0
            val before = matches()
            assertFalse(
                request(port, InetAddress.getByName(address).address, http.localPort, false)
            )
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (matches() <= before && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(
                "Unrecoverable $address must choose the pool blackhole: ${access.readText()}",
                matches() > before,
            )
        }

        fun client(
            port: Int,
            upstream: Int,
            settings: DnsSettings,
            routing: RoutingSettings,
            ipv6: Boolean,
            inbound: InboundSettings? = null,
            split: Boolean = false,
            serverUpstreams: Map<String, Int> = emptyMap(),
        ): Process {
            val owned =
                JsonParser.parseString(OwnedConfig.build(source(upstream), "127.0.0.1", port))
                    .asJsonObject
                    .apply {
                        serverUpstreams.forEach { (id, upstreamPort) ->
                            getAsJsonArray("outbounds")
                                .add(
                                    JsonParser.parseString(source(upstreamPort))
                                        .asJsonObject
                                        .apply {
                                            addProperty("tag", RoutingServerTags.outbound(id))
                                        }
                                )
                        }
                    }
                    .toString()
            val configured = inbound?.let { InboundConfigCompiler.configure(owned, it) } ?: owned
            val compiled =
                RoutingCompiler.compile(
                    DnsConfigCompiler.compile(configured, settings, ipv6, routing.takeIf { split }),
                    routing,
                )
            val config =
                inbound?.let { InboundConfigCompiler.applyTrafficPolicy(compiled, it) } ?: compiled
            return start("client-$port", config, port)
        }

        fun start(name: String, config: String, port: Int): Process {
            val configured =
                JsonParser.parseString(config).asJsonObject.apply {
                    add(
                        "log",
                        JsonObject().apply {
                            addProperty("access", File(dir, "$name.access").path)
                            addProperty("loglevel", "warning")
                        },
                    )
                }
            val file = File(dir, "$name.json").apply { writeText(configured.toString()) }
            val log = File(dir, "$name.log")
            val process =
                ProcessBuilder(executable!!.path, "run", "-c", file.path)
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .apply {
                        environment()["XRAY_LOCATION_ASSET"] = dir.path
                        environment().remove("XRAY_MPH_CACHE")
                    }
                    .start()
            children.add(process)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (process.isAlive && System.nanoTime() < deadline) {
                if (
                    runCatching {
                            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 50) }
                        }
                        .isSuccess
                )
                    return process
                Thread.sleep(20)
            }
            error("Core failed to start: ${log.readText()}")
        }

        fun stop(process: Process) {
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }
        }

        override fun close() {
            children.forEach(::stop)
            dns.close()
            http.close()
            dnsThread.join(2000)
            httpThread.join(2000)
            dir.deleteRecursively()
        }
    }
}
