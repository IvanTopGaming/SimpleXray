package com.simplexray.an.feature.settings.state.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun stableReleaseUpdatesReleaseCandidateOfSameVersion() {
        assertTrue(requireNotNull(compareReleaseVersions("1.11.0", "1.11.0-rc.1")) > 0)
        assertTrue(requireNotNull(compareReleaseVersions("1.11.0-rc.1", "1.11.0")) < 0)
    }

    @Test
    fun releaseCandidateNumbersCompareNumerically() {
        assertTrue(requireNotNull(compareReleaseVersions("1.11.0-rc.10", "1.11.0-rc.2")) > 0)
    }

    @Test
    fun olderStableReleaseDoesNotUpdateNewerReleaseCandidate() {
        assertTrue(requireNotNull(compareReleaseVersions("1.10.18", "1.11.0-rc.1")) < 0)
    }

    @Test
    fun betaPrecedesReleaseCandidateAndNumericIdentifiersPrecedeText() {
        assertTrue(requireNotNull(compareReleaseVersions("1.11.0-beta.2", "1.11.0-rc.1")) < 0)
        assertTrue(requireNotNull(compareReleaseVersions("1.11.0-2", "1.11.0-beta")) < 0)
        assertTrue(requireNotNull(compareReleaseVersions("1.11.0-rc", "1.11.0-rc.1")) < 0)
    }

    @Test
    fun optionalTagPrefixAndBuildMetadataDoNotChangePrecedence() {
        assertEquals(0, compareReleaseVersions("v1.11.0-rc.1+build.02", "1.11.0-rc.1+other"))
        assertEquals(0, compareReleaseVersions("v1.11.0+20261007", "1.11.0"))
        assertTrue(requireNotNull(compareReleaseVersions("1.11.0+build", "1.11.0-rc.1+build")) > 0)
    }

    @Test
    fun ordinaryVersionsRetainNumericOrdering() {
        assertTrue(requireNotNull(compareReleaseVersions("1.10.18", "1.10.9")) > 0)
        assertTrue(requireNotNull(compareReleaseVersions("2.0.0", "1.99.99")) > 0)
        assertEquals(0, compareReleaseVersions("1.10.18", "1.10.18"))
    }

    @Test
    fun numericIdentifiersDoNotOverflow() {
        assertTrue(
            requireNotNull(
                compareReleaseVersions("1.11.0-rc.999999999999999999999", "1.11.0-rc.10")
            ) > 0
        )
    }

    @Test
    fun malformedTagsAreRejectedInsteadOfComparingAsZero() {
        listOf(
                "",
                "latest",
                "https://github.com/owner/repo/releases/latest",
                "1.11",
                "1.11.0.1",
                "01.11.0",
                "1.11.0-rc.01",
                "1.11.0-",
                "1.11.0-rc..1",
                "1.11.0+",
                "1.11.0+build..1",
                "1.11.0+build+more",
                "1.11.0-rc.1/extra",
                " 1.11.0",
            )
            .forEach { tag ->
                assertNull(tag, compareReleaseVersions(tag, "1.11.0"))
                assertNull(tag, compareReleaseVersions("1.11.0", tag))
            }
    }
}
