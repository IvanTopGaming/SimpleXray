package com.simplexray.an.feature.profile

import android.app.Application
import com.google.protobuf.CodedOutputStream
import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.core.config.ConfigUtils
import com.simplexray.an.core.config.profile.ProfileGeodata
import com.simplexray.an.core.config.profile.ProfileOverrides
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.prefs.Preferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileCoreTest {
    private val server =
        """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":12345}]}}]}"""

    @Test
    fun manualSectionsAndReservedRuntimeServicesAreAcceptedByPinnedCore() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.socksUdpEnabled = false
        val config =
            AppConfig(prefs)
                .buildProfile(
                    server,
                    RoutingSettings(),
                    ProfileOverrides.decode(
                        """{
            "log":{"loglevel":"debug","access":"none","error":"","dnsLog":true,"maskAddress":"full"},
            "dns":{"servers":["1.1.1.1"],"queryStrategy":"UseIPv4","hosts":{"example.com":"1.2.3.4"}},
            "routing":{"rules":[{"domain":["full:example.com"],"outboundTag":"__sx_direct"}]},
            "policy":{"levels":{"0":{"handshake":5,"connIdle":60}},"system":{"statsInboundUplink":false,"statsInboundDownlink":false}},
            "stats":{}
        }"""
                    ),
                )
        assertTrue(ProfileGeodata.requiredFiles(config).isEmpty())
        withCore { core, dir ->
            validate(
                core,
                dir,
                ConfigUtils.injectStatsService("127.0.0.1", 19090, config, false, true),
            )
        }
    }

    @Test
    fun databasesReferencedOnlyInManualRulesAreDetectedAndLoaded() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        val config =
            AppConfig(prefs)
                .buildProfile(
                    server,
                    RoutingSettings(),
                    ProfileOverrides.decode(
                        """{
            "routing":{"rules":[
                {"domain":["geosite:test"],"outboundTag":"proxy"},
                {"ip":["geoip:test"],"outboundTag":"__sx_direct"}
            ]}
        }"""
                    ),
                )
        assertEquals(setOf("geoip.dat", "geosite.dat"), ProfileGeodata.requiredFiles(config))
        withCore { core, dir ->
            File(dir, "geosite.dat")
                .writeBytes(
                    message {
                        writeByteArray(
                            1,
                            message {
                                writeString(1, "TEST")
                                writeByteArray(
                                    2,
                                    message {
                                        writeEnum(1, 2)
                                        writeString(2, "example.com")
                                    },
                                )
                            },
                        )
                    }
                )
            File(dir, "geoip.dat")
                .writeBytes(
                    message {
                        writeByteArray(
                            1,
                            message {
                                writeString(1, "TEST")
                                writeByteArray(
                                    2,
                                    message {
                                        writeByteArray(1, byteArrayOf(127, 0, 0, 0))
                                        writeUInt32(2, 8)
                                    },
                                )
                            },
                        )
                    }
                )
            validate(core, dir, config)
        }
    }

    private fun withCore(block: (File, File) -> Unit) {
        val core = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)
        assumeTrue("Pinned host Xray required", core?.canExecute() == true)
        val dir = Files.createTempDirectory("profile-core").toFile()
        try {
            block(requireNotNull(core), dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun validate(core: File, directory: File, config: String) {
        val file = File(directory, "config.json").apply { writeText(config) }
        val output = File(directory, "validation.log")
        val builder =
            ProcessBuilder(core.path, "run", "-test", "-c", file.path)
                .redirectErrorStream(true)
                .redirectOutput(output)
        builder.environment()["XRAY_LOCATION_ASSET"] = directory.path
        val process = builder.start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(output.readText(), 0, process.exitValue())
        } finally {
            process.destroyForcibly()
        }
    }

    private fun message(block: CodedOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        CodedOutputStream.newInstance(output).apply {
            block()
            flush()
        }
        return output.toByteArray()
    }
}
