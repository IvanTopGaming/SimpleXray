package com.simplexray.an.feature.apps

import com.simplexray.an.BuildConfig
import com.simplexray.an.feature.apps.model.AppRoutingClipboard
import com.simplexray.an.feature.apps.model.AppRoutingMode
import org.junit.Assert.*
import org.junit.Test

class AppRoutingClipboardTest {
    @Test
    fun importsLegacyModesAndKeepsAllModeSelectionThroughRoundTrip() {
        assertEquals(
            AppRoutingMode.EXCLUDE,
            AppRoutingClipboard.decode("true\ncom.example.app").mode,
        )
        assertEquals(
            AppRoutingMode.INCLUDE,
            AppRoutingClipboard.decode("false\ncom.example.app").mode,
        )
        val decoded = AppRoutingClipboard.decode("all\ncom.example.app\ncom.example.other")
        assertEquals(AppRoutingMode.ALL, decoded.mode)
        assertEquals(setOf("com.example.app", "com.example.other"), decoded.packages)
        assertEquals(decoded, AppRoutingClipboard.decode(decoded.encode()))
        assertEquals(
            "true\ncom.example.app",
            AppRoutingClipboard.decode("true\ncom.example.app").encode(),
        )
        assertEquals(
            "false\ncom.example.app",
            AppRoutingClipboard.decode("false\ncom.example.app").encode(),
        )
    }

    @Test
    fun invalidModeOrPackageAndEmptyIncludeAreRejectedBeforeApplyingAnything() {
        listOf(
                "garbage\ncom.example.app",
                "all\nnot a package",
                "false",
                "false\n${BuildConfig.APPLICATION_ID}",
                "true\ncom.example.app\n#broken",
            )
            .forEach { text ->
                assertThrows(text, IllegalArgumentException::class.java) {
                    AppRoutingClipboard.decode(text)
                }
            }
    }

    @Test
    fun windowsClipboardAndTrailingNewlineAreAcceptedWithoutIncludingSelf() {
        val decoded =
            AppRoutingClipboard.decode(
                "true\r\ncom.example.app\r\n${BuildConfig.APPLICATION_ID}\r\n"
            )
        assertEquals(setOf("com.example.app"), decoded.packages)
    }
}
