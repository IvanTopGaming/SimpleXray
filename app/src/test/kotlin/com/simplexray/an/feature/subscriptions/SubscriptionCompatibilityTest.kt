package com.simplexray.an.feature.subscriptions

import com.google.gson.Gson
import com.simplexray.an.feature.subscriptions.model.Subscription
import org.junit.Assert.*
import org.junit.Test

class SubscriptionCompatibilityTest {
    private val legacy =
        """{"id":"one","name":"Old Provider","url":"https://example.com","lastUpdated":123,"files":["Old Provider - NL.json"]}"""

    @Test
    fun oldRegistryStillDisplaysItsNameAndKeepsFilenames() {
        val sub = Gson().fromJson(legacy, Subscription::class.java)
        assertEquals("Old Provider", sub.displayName)
        assertEquals(listOf("Old Provider - NL.json"), sub.files)
        assertNull(sub.usage)
        assertNull(sub.lastError)
    }

    @Test
    fun responseTitleDoesNotChangeStorageName() {
        val sub =
            Gson().fromJson(legacy, Subscription::class.java).copy(displayTitle = "New Provider")
        val restored = Gson().fromJson(Gson().toJson(sub), Subscription::class.java)
        assertEquals("New Provider", restored.displayName)
        assertEquals("Old Provider", restored.name)
        assertEquals(listOf("Old Provider - NL.json"), restored.files)
    }

    @Test
    fun legacyReaderIgnoresNewTitleWithoutLosingFiles() {
        val sub =
            Gson().fromJson(legacy, Subscription::class.java).copy(displayTitle = "New Provider")
        val restored = Gson().fromJson(Gson().toJson(sub), LegacySubscription::class.java)
        assertEquals("Old Provider", restored.name)
        assertEquals(listOf("Old Provider - NL.json"), restored.files)
    }

    private data class LegacySubscription(val name: String, val files: List<String>)
}
