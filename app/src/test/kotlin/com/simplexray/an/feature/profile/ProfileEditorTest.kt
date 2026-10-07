package com.simplexray.an.feature.profile

import android.app.Application
import com.google.gson.JsonParser
import com.simplexray.an.feature.profile.state.ProfileEditor
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.prefs.Preferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileEditorTest {
    private val source =
        """{"outbounds":[{"tag":"server","protocol":"socks","settings":{"servers":[{"address":"edge.example","port":1080}]}}]}"""

    private fun prefs(selected: Boolean = true): Preferences {
        val app = RuntimeEnvironment.getApplication()
        return Preferences(app).also {
            if (selected) {
                val file = File(app.filesDir, "profile-editor.json").apply { writeText(source) }
                it.selectedConfigPath = file.absolutePath
            } else {
                it.selectedConfigPath = null
            }
        }
    }

    @Test
    fun invalidOverridesPreserveSavedValueAndCompiledPreview() = runBlocking {
        val prefs = prefs()
        val editor = ProfileEditor(prefs)
        editor.save("""{"log":{"loglevel":"warning"}}""")
        val saved = prefs.profileOverridesJson
        val preview = editor.preview()
        for (invalid in
            listOf(
                "broken",
                """{"dns":{"servers":["1.1.1.1"],"clientIp":{}}}""",
                """{"dns":{"servers":[{"address":"1.1.1.1","serveExpiredTTL":"invalid"}]}}""",
                """{"dns":{"servers":["1.1.1.1"],"unknownField":true}}""",
                """{"dns":{"servers":[{"address":"1.1.1.1","unknownField":true}]}}""",
                """{"dns":{"servers":[{"address":"1.1.1.1","expectIPs":["not-an-ip"]}]}}""",
                """{"routing":{"rules":[{"ip":["not-an-ip"],"outboundTag":"proxy"}]}}""",
                """{"routing":{"rules":[{"source":["2001:db8::/129"],"outboundTag":"proxy"}]}}""",
                """{"routing":{"rules":[{"ip":["geoip:"],"outboundTag":"proxy"}]}}""",
                """{"outbounds":[]}""",
                """{"routing":{"rules":[{"type":"field","domain":["full:example.com"],"outboundTag":"missing"}]}}""",
            )) {
            try {
                editor.save(invalid)
                fail("Invalid override must fail")
            } catch (_: IllegalArgumentException) {
                assertEquals(saved, prefs.profileOverridesJson)
                assertEquals(preview, editor.preview())
            }
        }
    }

    @Test
    fun savedOverridesAppearInFullCompiledProfileAndSurviveReopening() = runBlocking {
        val prefs = prefs()
        val editor = ProfileEditor(prefs)
        val preview = requireNotNull(editor.save("""{"log":{"loglevel":"debug"}}"""))
        val root = JsonParser.parseString(preview).asJsonObject
        assertEquals("debug", root.getAsJsonObject("log")["loglevel"].asString)
        assertTrue(root.getAsJsonArray("inbounds").size() > 0)
        assertEquals("socks", root.getAsJsonArray("outbounds")[0].asJsonObject["protocol"].asString)
        assertTrue(root.has("dns"))
        assertTrue(root.has("routing"))
        assertEquals(
            preview,
            ProfileEditor(Preferences(RuntimeEnvironment.getApplication())).preview(),
        )
    }

    @Test
    fun noSelectedServerAllowsSavingButCannotClaimCompiledPreview() = runBlocking {
        val prefs = prefs(selected = false)
        val editor = ProfileEditor(prefs)
        assertNull(editor.save("""{"log":{"loglevel":"debug"}}"""))
        assertEquals(
            "debug",
            JsonParser.parseString(prefs.profileOverridesJson)
                .asJsonObject
                .getAsJsonObject("log")["loglevel"]
                .asString,
        )
        try {
            editor.preview()
            fail("Preview needs a selected server")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("сервер", ignoreCase = true))
        }
    }

    @Test
    fun validSaveCanReplaceCorruptStoredOverrides() = runBlocking {
        val prefs = prefs()
        prefs.profileOverridesJson = "broken"
        val preview = ProfileEditor(prefs).save("{}")
        assertNotNull(preview)
        assertEquals("{}", prefs.profileOverridesJson)
    }

    @Test
    fun unreadableSelectedProfileCannotPersistOverrides() = runBlocking {
        val prefs = prefs()
        prefs.profileOverridesJson = "{}"
        File(requireNotNull(prefs.selectedConfigPath)).writeText("broken")
        try {
            ProfileEditor(prefs).save("""{"log":{"loglevel":"debug"}}""")
            fail("Broken source must prevent saving")
        } catch (_: IllegalArgumentException) {
            assertEquals("{}", prefs.profileOverridesJson)
        }
    }

    @Test
    fun previewUsesCurrentRoutingPresetAndManualRoutingTakesPriority() = runBlocking {
        val prefs = prefs()
        val before = prefs.readRoutingPreset()
        prefs.applyRoutingPreset(
            before.copy(
                routing = RoutingSettings(defaultRoute = RouteTarget.BLOCK, bypassLan = false)
            )
        )
        val editor = ProfileEditor(prefs)
        val initial = JsonParser.parseString(editor.preview()).asJsonObject
        assertTrue(
            initial.getAsJsonObject("routing").getAsJsonArray("rules").any {
                it.asJsonObject["outboundTag"]?.asString == "__sx_block"
            }
        )
        val manual =
            """{"routing":{"domainStrategy":"AsIs","rules":[{"type":"field","domain":["full:manual.example"],"outboundTag":"__sx_direct"}]}}"""
        val preview = JsonParser.parseString(requireNotNull(editor.save(manual))).asJsonObject
        val rules = preview.getAsJsonObject("routing").getAsJsonArray("rules")
        assertTrue(
            rules.any {
                it.asJsonObject.getAsJsonArray("domain")?.any { domain ->
                    domain.asString == "full:manual.example"
                } == true
            }
        )
        assertFalse(rules.any { it.asJsonObject["outboundTag"]?.asString == "__sx_block" })
    }
}
