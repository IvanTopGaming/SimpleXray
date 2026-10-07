package com.simplexray.an.feature.routing

import com.simplexray.an.feature.routing.model.RoutingServerRef
import com.simplexray.an.feature.routing.server.RoutingServerOption
import com.simplexray.an.feature.routing.ui.isRoutingServerAvailable
import org.junit.Assert.*
import org.junit.Test

class RoutingServerAvailabilityTest {
    @Test
    fun manualSelectionFollowsFileIdentityAcrossProfileEdits() {
        val original = RoutingServerRef("manual.json", "Manual", fingerprint = "a".repeat(64))
        val edited = original.copy(fingerprint = "b".repeat(64))
        assertTrue(isRoutingServerAvailable(original, listOf(option(edited))))
        assertFalse(
            isRoutingServerAvailable(original, listOf(option(edited.copy(fileName = "other.json"))))
        )
        assertFalse(
            isRoutingServerAvailable(original, listOf(option(edited.copy(subscriptionId = "sub"))))
        )
    }

    @Test
    fun subscriptionIdentityHandlesRenumberingAndRequiresPinnedFingerprintForDuplicates() {
        val original = RoutingServerRef("old.json", "Server", "sub", "a".repeat(64))
        val changed = original.copy(fileName = "new.json", fingerprint = "b".repeat(64))
        assertTrue(isRoutingServerAvailable(original, listOf(option(changed))))
        assertFalse(
            isRoutingServerAvailable(
                original.copy(matchFingerprint = true),
                listOf(option(changed)),
            )
        )
        val sameServer = original.copy(fileName = "renumbered.json")
        assertTrue(
            isRoutingServerAvailable(
                original.copy(matchFingerprint = true),
                listOf(option(sameServer), option(changed)),
            )
        )
        assertFalse(
            isRoutingServerAvailable(
                original,
                listOf(option(changed), option(changed.copy(fileName = "third.json"))),
            )
        )
        assertFalse(isRoutingServerAvailable(original, emptyList()))
    }

    private fun option(reference: RoutingServerRef) = RoutingServerOption(reference, "Group")
}
