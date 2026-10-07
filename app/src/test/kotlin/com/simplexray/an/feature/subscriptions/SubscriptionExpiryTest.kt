package com.simplexray.an.feature.subscriptions

import com.simplexray.an.feature.subscriptions.data.subscriptionExpiryText
import org.junit.Assert.*
import org.junit.Test

class SubscriptionExpiryTest {
    @Test
    fun expiryDistinguishesUnknownUnlimitedExpiredAndRoundedDays() {
        assertEquals("Срок действия неизвестен", subscriptionExpiryText(null, 1000))
        assertEquals("Без срока действия", subscriptionExpiryText(0, 1000))
        assertEquals("Срок действия истёк", subscriptionExpiryText(1000, 1000))
        assertEquals("Продление через 1 дн.", subscriptionExpiryText(1001, 1000))
        assertEquals("Продление через 2 дн.", subscriptionExpiryText(87401, 1000))
    }

    @Test
    fun hugeEpochCannotOverflowIntoExpiredState() {
        assertTrue(subscriptionExpiryText(Long.MAX_VALUE, 1000).startsWith("Продление через "))
    }
}
