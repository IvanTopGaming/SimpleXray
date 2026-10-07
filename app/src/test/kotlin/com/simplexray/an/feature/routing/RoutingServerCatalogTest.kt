package com.simplexray.an.feature.routing

import com.simplexray.an.feature.routing.model.RoutingServerRef
import com.simplexray.an.feature.routing.server.RoutingServerCatalog
import com.simplexray.an.feature.subscriptions.model.Subscription
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoutingServerCatalogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun oldCoreDuplicateIdentitySurvivesRuntimeChainMigration() {
        val file =
            temporary.newFile("Feed - One.json").apply {
                writeText(
                    """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"address":"one.example","port":1080},"proxySettings":{"tag":"hop"}},{"tag":"hop","protocol":"socks","settings":{"address":"hop.example","port":1081}}]}"""
                )
            }
        val ref =
            RoutingServerRef(
                file.name,
                "One",
                "subscription-id",
                "ee01afbc54e17fe238585a9ecde3027c4be1bdbc7ecefeb6eaf420ff3813381c",
                true,
            )
        val sub = subscription(file)
        assertEquals(
            ref.fingerprint,
            RoutingServerCatalog.options(listOf(file), listOf(sub)).single().reference.fingerprint,
        )
        assertEquals(file.readText(), RoutingServerCatalog.read(ref, listOf(file), listOf(sub)))
    }

    @Test
    fun optionsGroupStandaloneAndSubscriptionServersWithoutConnectionDetails() {
        val manual = server("Manual.json")
        val owned = server("Feed - One.json")
        val subscription = subscription(owned).copy(displayTitle = "My subscription")
        val options = RoutingServerCatalog.options(listOf(manual, owned), listOf(subscription))
        assertEquals(listOf("Ручные серверы", "My subscription"), options.map { it.group })
        assertEquals(listOf("Manual", "One"), options.map { it.reference.name })
        assertNull(options.first().reference.subscriptionId)
        assertEquals(subscription.id, options.last().reference.subscriptionId)
        assertTrue(options.all { it.reference.fingerprint.matches(Regex("[a-f0-9]{64}")) })
        assertFalse(options.toString().contains("test-password"))
        assertFalse(options.toString().contains(subscription.url))
    }

    @Test
    fun uniqueSubscriptionNameSurvivesUpdatedHostCredentialsAndSubscriptionRename() {
        val original = server("Feed - One.json")
        val subscription = subscription(original)
        val reference = option(original, subscription)
        val refreshed = server("Renamed - One.json", "updated.example", "new-password")
        val updated = subscription.copy(name = "Renamed", files = listOf(refreshed.name))
        assertEquals(
            refreshed,
            RoutingServerCatalog.resolve(reference, listOf(refreshed), listOf(updated)),
        )
    }

    @Test
    fun duplicateSubscriptionNamesFollowProfileWhenFeedOrderChanges() {
        val first = server("Feed - One.json", "first.example")
        val second = server("Feed - One (2).json", "second.example")
        val subscription = subscription(first, second)
        val reference = option(first, subscription, listOf(first, second))
        first.writeText(config("second.example"))
        second.writeText(config("first.example"))
        assertEquals(
            second,
            RoutingServerCatalog.resolve(reference, listOf(first, second), listOf(subscription)),
        )
    }

    @Test
    fun duplicateProfilesMustHaveOneFingerprintMatchAndNeverFallBackToFilename() {
        val first = server("Feed - One.json", "first.example")
        val second = server("Feed - One (2).json", "second.example")
        val subscription = subscription(first, second)
        val reference = option(first, subscription, listOf(first, second))
        second.writeText(first.readText())
        unresolved(reference, listOf(first, second), listOf(subscription))
        first.writeText(config("other.example"))
        second.writeText(first.readText())
        unresolved(reference, listOf(first, second), listOf(subscription))
        unresolved(reference.copy(fingerprint = ""), listOf(first, second), listOf(subscription))
    }

    @Test
    fun selectedDuplicateCannotSwitchToDifferentLastSurvivor() {
        val first = server("Feed - One.json", "first.example")
        val second = server("Feed - One (2).json", "second.example")
        val subscription = subscription(first, second)
        val reference = option(first, subscription, listOf(first, second))
        assertTrue(reference.matchFingerprint)
        val refreshed = subscription.copy(files = listOf(first.name))
        first.writeText(second.readText())
        unresolved(reference, listOf(first), listOf(refreshed))
        first.writeText(config("first.example"))
        assertEquals(
            first,
            RoutingServerCatalog.resolve(reference, listOf(first), listOf(refreshed)),
        )
    }

    @Test
    fun formerlyUniqueNameRequiresFingerprintWhenItBecomesAmbiguous() {
        val first = server("Feed - One.json", "first.example")
        val original = subscription(first)
        val reference = option(first, original)
        assertFalse(reference.matchFingerprint)
        val second = server("Feed - One (2).json", "first.example")
        first.writeText(config("second.example"))
        val refreshed = subscription(first, second)
        assertEquals(
            second,
            RoutingServerCatalog.resolve(reference, listOf(first, second), listOf(refreshed)),
        )
        second.writeText(config("third.example"))
        unresolved(reference, listOf(first, second), listOf(refreshed))
    }

    @Test
    fun readFollowsTheMatchingDuplicateSnapshotAfterRefreshReordersFiles() {
        val first = server("Feed - One.json", "first.example")
        val second = server("Feed - One (2).json", "second.example")
        val subscription = subscription(first, second)
        val reference = option(first, subscription, listOf(first, second))
        val expected = first.readText()
        first.writeText(second.readText())
        second.writeText(expected)
        assertEquals(
            expected,
            RoutingServerCatalog.read(reference, listOf(first, second), listOf(subscription)),
        )
        second.writeText(config("third.example"))
        assertThrows(IllegalArgumentException::class.java) {
            RoutingServerCatalog.read(reference, listOf(first, second), listOf(subscription))
        }
    }

    @Test
    fun deletedOrChangedOwnerNeverResolvesToOtherSubscriptionsOrStandaloneFiles() {
        val owned = server("Feed - One.json")
        val subscription = subscription(owned)
        val reference = option(owned, subscription)
        unresolved(reference, listOf(owned), emptyList())
        unresolved(reference, listOf(owned), listOf(subscription.copy(id = "another")))
        unresolved(reference, listOf(owned), listOf(subscription.copy(files = emptyList())))
        unresolved(reference, emptyList(), listOf(subscription))
    }

    @Test
    fun standaloneRequiresExactUnownedFilename() {
        val manual = server("Manual.json")
        val reference = RoutingServerCatalog.options(listOf(manual), emptyList()).single().reference
        manual.writeText(config("changed.example"))
        assertEquals(manual, RoutingServerCatalog.resolve(reference, listOf(manual), emptyList()))
        unresolved(reference, listOf(manual), listOf(subscription(manual)))
        unresolved(reference, listOf(server("Renamed.json")), emptyList())
    }

    @Test
    fun fingerprintIgnoresObjectKeyOrderDisplayMetadataAndClientConfiguration() {
        val original = server("One.json")
        val reordered =
            temporary.newFile("Two.json").apply {
                writeText(
                    """{"remarks":"Different","dns":{"servers":["192.0.2.1"]},"outbounds":[{"settings":{"servers":[{"password":"test-password","port":443,"address":"one.example"}]},"protocol":"trojan","tag":"changed"}]}"""
                )
            }
        val references = RoutingServerCatalog.options(listOf(original, reordered), emptyList())
        assertEquals(2, references.size)
        assertEquals(
            references.first().reference.fingerprint,
            references.last().reference.fingerprint,
        )
    }

    @Test
    fun malformedProfilesAreNotSelectableAndErrorsDoNotExposeTheirContents() {
        val malformed =
            temporary.newFile("Invalid.json").apply { writeText("private-invalid-json") }
        assertTrue(RoutingServerCatalog.options(listOf(malformed), emptyList()).isEmpty())
    }

    @Test
    fun referenceValidationRejectsTraversalAndUnboundedFields() {
        listOf(
                "../One.json",
                "/One.json",
                "dir/One.json",
                "dir\\One.json",
                ".",
                "..",
                "bad\u0000.json",
            )
            .forEach { filename ->
                assertThrows(IllegalArgumentException::class.java) {
                    RoutingServerRef(filename, "One").validate()
                }
            }
        listOf(
                RoutingServerRef("One.json", " "),
                RoutingServerRef("x".repeat(1024) + ".json", "One"),
                RoutingServerRef("One.json", "x".repeat(2048)),
                RoutingServerRef("One.json", "One", subscriptionId = ""),
                RoutingServerRef("One.json", "One", fingerprint = "not-a-hash"),
                RoutingServerRef(
                    "One.json",
                    "One",
                    subscriptionId = "owner",
                    matchFingerprint = true,
                ),
                RoutingServerRef(
                    "One.json",
                    "One",
                    fingerprint = "a".repeat(64),
                    matchFingerprint = true,
                ),
            )
            .forEach { reference ->
                assertThrows(IllegalArgumentException::class.java) { reference.validate() }
            }
        RoutingServerRef("One.json", "One").validate()
    }

    private fun option(file: File, subscription: Subscription, files: List<File> = listOf(file)) =
        RoutingServerCatalog.options(files, listOf(subscription))
            .single { it.reference.fileName == file.name }
            .reference

    private fun unresolved(
        reference: RoutingServerRef,
        files: List<File>,
        subscriptions: List<Subscription>,
    ) {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                RoutingServerCatalog.resolve(reference, files, subscriptions)
            }
        assertTrue(error.message.orEmpty().contains("Выбери сервер заново"))
        assertFalse(error.message.orEmpty().contains("test-password"))
    }

    private fun server(
        name: String,
        host: String = "one.example",
        password: String = "test-password",
    ) = temporary.newFile(name).apply { writeText(config(host, password)) }

    private fun config(host: String, password: String = "test-password") =
        """{"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"$host","port":443,"password":"$password"}]}}]}"""

    private fun subscription(vararg files: File) =
        Subscription(
            "subscription-id",
            "Feed",
            "https://subscription.invalid/private-token",
            0L,
            files.map { it.name },
        )
}
