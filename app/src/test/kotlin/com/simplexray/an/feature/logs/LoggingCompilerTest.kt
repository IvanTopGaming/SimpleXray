package com.simplexray.an.feature.logs

import android.app.Application
import com.google.gson.JsonParser
import com.simplexray.an.core.config.ConfigUtils
import com.simplexray.an.core.config.logging.LoggingConfigCompiler
import com.simplexray.an.feature.logs.model.LogSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LoggingCompilerTest {
    @Test
    fun defaultsAndStableJsonRoundTrip() {
        assertEquals(LogSettings(), LogSettings.decode(null))
        assertEquals(LogSettings(), LogSettings.decode("{}"))
        val settings = LogSettings("debug", true, true, true, false)
        assertEquals(settings, LogSettings.decode(settings.encode()))
        assertEquals(settings.encode(), LogSettings.decode(settings.encode()).encode())
    }

    @Test
    fun rejectsMalformedSavedSettings() {
        listOf("[]", "null", "", "{\"level\":\"verbose\"}", "{\"trafficStats\":\"false\"}")
            .forEach {
                assertThrows(IllegalArgumentException::class.java) { LogSettings.decode(it) }
            }
    }

    @Test
    fun logConfigUsesConsoleAndPinnedCoreMaskField() {
        val original =
            """{"log":{"access":"/tmp/access","error":"/tmp/error"},"outbounds":[{"protocol":"freedom"}]}"""
        val result =
            JsonParser.parseString(
                    LoggingConfigCompiler.compile(original, LogSettings("info", true, true, true))
                )
                .asJsonObject
        val log = result.getAsJsonObject("log")
        assertEquals("", log["access"].asString)
        assertEquals("", log["error"].asString)
        assertEquals("info", log["loglevel"].asString)
        assertEquals("full", log["maskAddress"].asString)
        assertTrue(log["dnsLog"].asBoolean)
        assertEquals(
            "freedom",
            result.getAsJsonArray("outbounds")[0].asJsonObject["protocol"].asString,
        )
    }

    @Test
    fun defaultsEnableAccessAndDnsWithInfoLevel() {
        val log =
            JsonParser.parseString(LoggingConfigCompiler.compile("{}", LogSettings()))
                .asJsonObject
                .getAsJsonObject("log")
        assertEquals("", log["access"].asString)
        assertEquals("info", log["loglevel"].asString)
        assertTrue(log["dnsLog"].asBoolean)
        assertEquals("", log["maskAddress"].asString)
    }

    @Test
    fun trafficOffKeepsStatsServiceForSystemReadiness() {
        val result =
            JsonParser.parseString(ConfigUtils.injectStatsService("127.0.0.1", 18085, "{}", false))
                .asJsonObject
        assertEquals(
            "StatsService",
            result.getAsJsonObject("api").getAsJsonArray("services")[0].asString,
        )
        assertTrue(result.has("stats"))
        val system = result.getAsJsonObject("policy").getAsJsonObject("system")
        listOf(
                "statsInboundUplink",
                "statsInboundDownlink",
                "statsOutboundUplink",
                "statsOutboundDownlink",
            )
            .forEach {
                assertFalse(system[it].asBoolean)
            }
        assertFalse(LoggingConfigCompiler.trafficStatsEnabled(result.toString(), true))
    }

    @Test
    fun compilerAppliesTrafficSettingsAndPreservesOtherPolicyKeys() {
        val compiled =
            LoggingConfigCompiler.compile(
                """{"policy":{"levels":{"0":{"handshake":8}},"system":{"statsInboundUplink":true,"statsOutboundUplink":true}}}""",
                LogSettings(trafficStats = false),
            )
        val policy = JsonParser.parseString(compiled).asJsonObject.getAsJsonObject("policy")
        assertEquals(8, policy.getAsJsonObject("levels").getAsJsonObject("0")["handshake"].asInt)
        assertFalse(policy.getAsJsonObject("system")["statsInboundUplink"].asBoolean)
        assertFalse(policy.getAsJsonObject("system")["statsOutboundUplink"].asBoolean)
    }

    @Test
    fun statsInjectionPreservesExplicitOverridesWhenRequested() {
        val original =
            """{"policy":{"system":{"statsInboundUplink":false,"statsInboundDownlink":false}}}"""
        val result =
            ConfigUtils.injectStatsService(
                "127.0.0.1",
                18085,
                original,
                true,
                preserveTrafficPolicy = true,
            )
        assertFalse(LoggingConfigCompiler.trafficStatsEnabled(result, true))
        assertTrue(
            JsonParser.parseString(result)
                .asJsonObject
                .getAsJsonObject("policy")
                .getAsJsonObject("system")["statsOutboundUplink"]
                .asBoolean
        )
        assertTrue(
            LoggingConfigCompiler.trafficStatsEnabled(
                ConfigUtils.injectStatsService("127.0.0.1", 18085, original),
                false,
            )
        )
    }

    @Test
    fun effectiveTrafficFlagsFollowBothInboundCounters() {
        assertTrue(LoggingConfigCompiler.trafficStatsEnabled("{}", true))
        assertFalse(LoggingConfigCompiler.trafficStatsEnabled("{}", false))
        assertFalse(
            LoggingConfigCompiler.trafficStatsEnabled(
                """{"policy":{"system":{"statsInboundUplink":true,"statsInboundDownlink":false}}}""",
                true,
            )
        )
        assertTrue(
            LoggingConfigCompiler.trafficStatsEnabled(
                ConfigUtils.injectStatsService("127.0.0.1", 18085, "{}"),
                false,
            )
        )
    }
}
