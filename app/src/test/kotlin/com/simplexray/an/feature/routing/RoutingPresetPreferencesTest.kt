package com.simplexray.an.feature.routing

import android.app.Application
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import androidx.preference.PreferenceManager
import com.simplexray.an.R
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlock
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.prefs.PrefsContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoutingPresetPreferencesTest {
    private val app
        get() = RuntimeEnvironment.getApplication()

    private val prefs
        get() = Preferences(app)

    private val replacement =
        RoutingPreset(
            RoutingSettings(
                defaultRoute = RouteTarget.DIRECT,
                rules =
                    listOf(
                        RoutingRule(
                            id = "new",
                            name = "Replacement",
                            values = listOf("example.test"),
                        )
                    ),
            ),
            "https://new.example/geoip.dat",
            "https://new.example/geosite.dat",
        )

    @Test
    fun replacementPublishesAllFieldsTogetherAndPreservesUnrelatedPreferences() {
        prefs.routingSettingsJson =
            RoutingSettings(
                    rules =
                        listOf(RoutingRule(id = "old", name = "Old", values = listOf("old.test")))
                )
                .encode()
        prefs.dnsSettingsJson = "keep-dns"
        prefs.selectedConfigPath = "/keep/server.json"
        val observed = mutableListOf<RoutingPreset>()
        val shared = PreferenceManager.getDefaultSharedPreferences(app)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            observed += prefs.readRoutingPreset()
        }
        shared.registerOnSharedPreferenceChangeListener(listener)
        try {
            prefs.applyRoutingPreset(replacement)
            assertEquals(replacement, prefs.readRoutingPreset())
            assertFalse(observed.isEmpty())
            assertTrue(observed.all { it == replacement })
            assertEquals("keep-dns", prefs.dnsSettingsJson)
            assertEquals("/keep/server.json", prefs.selectedConfigPath)
            assertEquals(
                mapOf("geoip.dat" to replacement.geoipUrl, "geosite.dat" to replacement.geositeUrl),
                prefs.pendingGeodata,
            )
        } finally {
            shared.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    @Test
    fun largeRoutingSnapshotUsesFileDescriptorAndLeavesNoCacheFiles() {
        val text = "#" + "x".repeat(1_200_000)
        val large =
            replacement.copy(
                routing =
                    RoutingSettings(
                        blocks =
                            listOf(
                                RoutingBlock(RouteTarget.DIRECT, text),
                                RoutingBlock(RouteTarget.PROXY),
                                RoutingBlock(RouteTarget.BLOCK),
                            )
                    )
            )
        prefs.applyRoutingPreset(large)
        val before = app.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        requireNotNull(
                app.contentResolver.openFileDescriptor(
                    PrefsContract.ROUTING_PRESET_SNAPSHOT_URI,
                    "r",
                )
            )
            .let { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    assertTrue(
                        "Large snapshots must stream outside Binder",
                        input.readBytes().size > 1024 * 1024,
                    )
                }
            }
        val captured = prefs.readRoutingPresetState()
        prefs.applyRoutingPreset(replacement)
        assertEquals(large, captured.first)
        assertEquals(
            mapOf("geoip.dat" to replacement.geoipUrl, "geosite.dat" to replacement.geositeUrl),
            captured.second,
        )
        assertEquals(before, app.cacheDir.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test
    fun missingKeysExportResourceDefaultsAndSameDefaultUrlsNeedNoRefresh() {
        val snapshot = prefs.readRoutingPreset()
        assertEquals(app.getString(R.string.geoip_url), snapshot.geoipUrl)
        assertEquals(app.getString(R.string.geosite_url), snapshot.geositeUrl)
        prefs.applyRoutingPreset(snapshot)
        assertTrue(prefs.pendingGeodata.isEmpty())
    }

    @Test
    fun invalidPresetLeavesEveryPreferenceUntouched() {
        prefs.applyRoutingPreset(replacement)
        val before = PreferenceManager.getDefaultSharedPreferences(app).all
        assertThrows(IllegalArgumentException::class.java) {
            prefs.applyRoutingPreset(replacement.copy(geoipUrl = "file:///bad.dat"))
        }
        assertEquals(before, PreferenceManager.getDefaultSharedPreferences(app).all)
    }

    @Test
    fun sameSourceRetainsPendingRefreshAndStaleAcknowledgementCannotClearNewSource() {
        prefs.applyRoutingPreset(replacement)
        prefs.applyRoutingPreset(replacement.copy(routing = RoutingSettings()))
        assertEquals(replacement.geoipUrl, prefs.pendingGeodata["geoip.dat"])
        val newer = replacement.copy(geoipUrl = "https://newer.example/geoip.dat")
        prefs.applyRoutingPreset(newer)
        prefs.acknowledgeGeodata("geoip.dat", replacement.geoipUrl)
        assertEquals(newer.geoipUrl, prefs.pendingGeodata["geoip.dat"])
        prefs.acknowledgeGeodata("geoip.dat", newer.geoipUrl)
        assertFalse(prefs.pendingGeodata.containsKey("geoip.dat"))
        assertEquals(replacement.geositeUrl, prefs.pendingGeodata["geosite.dat"])
    }

    @Test
    fun capturedPresetAndPendingSourcesStayCoherentAcrossLaterReplacementAndAcknowledgement() {
        prefs.applyRoutingPreset(replacement)
        val captured = prefs.readRoutingPresetState()
        val newer =
            replacement.copy(
                routing = RoutingSettings(defaultRoute = RouteTarget.BLOCK),
                geoipUrl = "https://newer.example/geoip.dat",
                geositeUrl = "https://newer.example/geosite.dat",
            )
        prefs.applyRoutingPreset(newer)
        prefs.acknowledgeGeodata("geosite.dat", newer.geositeUrl)
        assertEquals(replacement, captured.first)
        assertEquals(
            mapOf(
                "geoip.dat" to "https://new.example/geoip.dat",
                "geosite.dat" to "https://new.example/geosite.dat",
            ),
            captured.second,
        )
        val current = prefs.readRoutingPresetState()
        assertEquals(newer, current.first)
        assertEquals(mapOf("geoip.dat" to "https://newer.example/geoip.dat"), current.second)
    }

    @Test
    fun successfulManualPublicationClearsOnlyItsOwnPendingMarker() {
        prefs.applyRoutingPreset(replacement)
        prefs.geoipUrl = "https://manual.example/geoip.dat"
        assertEquals("https://manual.example/geoip.dat", prefs.geoipUrl)
        assertEquals(mapOf("geosite.dat" to replacement.geositeUrl), prefs.pendingGeodata)
        prefs.geositeUrl = replacement.geositeUrl
        assertTrue(prefs.pendingGeodata.isEmpty())
    }

    @Test
    fun unknownDatabaseAcknowledgementIsRejectedWithoutChanges() {
        prefs.applyRoutingPreset(replacement)
        val before = PreferenceManager.getDefaultSharedPreferences(app).all
        assertThrows(IllegalArgumentException::class.java) {
            prefs.acknowledgeGeodata("../geoip.dat", replacement.geoipUrl)
        }
        assertEquals(before, PreferenceManager.getDefaultSharedPreferences(app).all)
    }
}
