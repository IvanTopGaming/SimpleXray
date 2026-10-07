package com.simplexray.an.feature.subscriptions

import com.simplexray.an.feature.subscriptions.data.subscriptionInstallationId
import com.simplexray.an.feature.subscriptions.model.SubscriptionUpdateInterval
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubscriptionSettingsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun missingAndUnsupportedIntervalsUseHourlySchedule() {
        listOf(null, -1, 0, 1, 90, Int.MAX_VALUE).forEach { value ->
            val interval = SubscriptionUpdateInterval.fromMinutes(value)
            assertEquals(3600000L, interval.millis)
            assertEquals(900000L, interval.flexMillis)
        }
    }

    @Test
    fun selectedIntervalsProduceAndroidCompatibleSchedules() {
        listOf(15 to 900000L, 60 to 3600000L, 360 to 21600000L, 1440 to 86400000L).forEach {
            (minutes, millis) ->
            val interval = SubscriptionUpdateInterval.fromMinutes(minutes)
            assertEquals(millis, interval.millis)
            assertTrue(interval.flexMillis >= 300000L)
            assertTrue(interval.flexMillis <= interval.millis)
        }
    }

    @Test
    fun installationIdentifierSurvivesRepeatedReadsAndDiffersBetweenInstallations() {
        val firstInstallation = temporaryFolder.newFolder()
        val identifier = subscriptionInstallationId(firstInstallation)
        assertEquals(4, UUID.fromString(identifier).version())
        assertEquals(identifier, subscriptionInstallationId(firstInstallation))
        assertNotEquals(identifier, subscriptionInstallationId(temporaryFolder.newFolder()))
    }

    @Test
    fun concurrentFirstReadsShareOneInstallationIdentifier() {
        val directory = temporaryFolder.newFolder()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val identifiers =
                executor.invokeAll(List(12) { Callable { subscriptionInstallationId(directory) } })
            assertEquals(1, identifiers.map { it.get() }.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }
}
