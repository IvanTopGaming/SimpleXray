package com.simplexray.an.feature.subscriptions

import com.simplexray.an.feature.subscriptions.data.subscriptionTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionTitleTest {
    @Test
    fun usesResponseTitleBeforeFilename() {
        assertEquals(
            "Provider",
            subscriptionTitle(
                "https://example.com/secret",
                " Provider ",
                "attachment; filename=ignored.txt",
            ),
        )
    }

    @Test
    fun decodesUtf8Title() {
        assertEquals(
            "Подписка",
            subscriptionTitle("https://example.com/token", "base64:0J/QvtC00L/QuNGB0LrQsA==", null),
        )
    }

    @Test
    fun fallsBackToDomainWithoutSecretPathOrPort() {
        assertEquals(
            "example.com",
            subscriptionTitle("https://example.com:8443/secret?token=private", "base64:???", null),
        )
    }

    @Test
    fun usesContentDispositionWhenTitleIsMissing() {
        assertEquals(
            "My Plan",
            subscriptionTitle("https://example.com", null, "attachment; filename=\"My Plan.txt\""),
        )
    }

    @Test
    fun decodesExtendedFilenameWithoutChangingPlusSigns() {
        assertEquals(
            "Plan+One",
            subscriptionTitle(
                "https://example.com",
                null,
                "attachment; filename*=UTF-8''Plan%2BOne.yaml",
            ),
        )
    }
}
