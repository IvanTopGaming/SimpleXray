package com.simplexray.an.feature.dns

import android.app.Application
import com.google.gson.JsonParser
import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.core.config.routing.RoutingCompiler
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.prefs.Preferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FakeDnsRuntimeBaselineTest {
    @Test
    fun defaultRuntimeProvidesFakeDnsAndInterceptsClientDns() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        val source =
            """{"outbounds":[{"protocol":"trojan","tag":"proxy","settings":{"servers":[{"address":"127.0.0.1","port":443,"password":"test"}]}}]}"""
        val config =
            JsonParser.parseString(
                    RoutingCompiler.compile(AppConfig(prefs).build(source), RoutingSettings())
                )
                .asJsonObject
        assertNotNull("Default runtime must include FakeDNS pools", config["fakedns"])
        assertTrue(
            config.getAsJsonArray("outbounds").any {
                it.asJsonObject["protocol"].asString == "dns"
            }
        )
        val rules = config.getAsJsonObject("routing").getAsJsonArray("rules")
        assertTrue(rules.any { it.asJsonObject["port"]?.asString == "53" })
        assertTrue(
            config.getAsJsonArray("inbounds").any {
                it.asJsonObject.getAsJsonObject("sniffing")?.getAsJsonArray("destOverride")?.any {
                    value ->
                    value.asString == "fakedns"
                } == true
            }
        )
    }
}
