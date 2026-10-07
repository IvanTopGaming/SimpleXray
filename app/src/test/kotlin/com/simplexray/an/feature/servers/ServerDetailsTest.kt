package com.simplexray.an.feature.servers

import com.simplexray.an.feature.servers.model.serverDetails
import org.junit.Assert.*
import org.junit.Test

class ServerDetailsTest {
    @Test
    fun detailsUseTheProxyOutboundAndCountryFromTheName() {
        val details =
            serverDetails(
                "🇩🇪 Berlin.json",
                """{"outbounds":[{"protocol":"freedom"},{"tag":"proxy","protocol":"vless"}]}""",
            )

        assertEquals("DE", details.code)
        assertEquals("Германия", details.country)
        assertEquals("VLESS", details.protocol)
    }

    @Test
    fun unreadableConfigurationKeepsTheCountryWithoutAnInventedProtocol() {
        val details = serverDetails("🇫🇷 Paris.json", null)

        assertEquals("FR", details.code)
        assertEquals("Франция", details.country)
        assertNull(details.protocol)
    }

    @Test
    fun updatedContentAtTheSameFilenameReplacesTheProtocol() {
        val name = "server.json"
        val original = serverDetails(name, """{"outbounds":[{"protocol":"vless"}]}""")
        val updated = serverDetails(name, """{"outbounds":[{"protocol":"trojan"}]}""")

        assertEquals("VLESS", original.protocol)
        assertEquals("TROJAN", updated.protocol)
        assertEquals("—", updated.code)
        assertNull(updated.country)
    }

    @Test
    fun missingNullOrMalformedOutboundsKeepCountryWithoutProtocol() {
        for (content in
            listOf("{}", "{\"outbounds\":null}", "{\"outbounds\":{}}", "[]", "{broken")) {
            val details = serverDetails("🇩🇪 Berlin.json", content)

            assertEquals("DE", details.code)
            assertNull(details.protocol)
        }
    }

    @Test
    fun nonObjectOutboundItemsAreSkippedBeforeSelectingTheProxy() {
        val details =
            serverDetails(
                "server.json",
                """{"outbounds":[null,42,"bad",[],{"protocol":"freedom"},{"protocol":"trojan"}]}""",
            )

        assertEquals("TROJAN", details.protocol)
    }

    @Test
    fun proxyWithoutAUsableProtocolDoesNotBorrowAnotherOutboundProtocol() {
        for (proxy in
            listOf(
                """{"tag":"proxy"}""",
                """{"tag":"proxy","protocol":null}""",
                """{"tag":"proxy","protocol":"   "}""",
            )) {
            val content = """{"outbounds":[$proxy,{"protocol":"trojan"}]}"""

            assertNull(serverDetails("server.json", content).protocol)
        }
    }
}
