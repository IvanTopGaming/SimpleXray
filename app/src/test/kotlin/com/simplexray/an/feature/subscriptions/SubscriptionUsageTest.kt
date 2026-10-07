package com.simplexray.an.feature.subscriptions

import com.simplexray.an.feature.subscriptions.data.parseSubscriptionUsage
import com.simplexray.an.feature.subscriptions.model.SubscriptionUsage
import org.junit.Assert.*
import org.junit.Test

class SubscriptionUsageTest {
    @Test
    fun completeHeaderDerivesRemainingAndProgress() {
        val usage =
            parseSubscriptionUsage("upload=100; download=300; total=1000; expire=2000000000")!!
        assertEquals(400L, usage.used)
        assertEquals(600L, usage.remaining)
        assertEquals(.4f, usage.progress!!, .0001f)
        assertEquals(2000000000L, usage.expire)
    }

    @Test
    fun absentAndInvalidNumbersStayUnknown() {
        assertNull(parseSubscriptionUsage(null))
        assertNull(
            parseSubscriptionUsage("upload=-1; download=bad; total=9223372036854775808; expire=1e3")
        )
        val usage = parseSubscriptionUsage("download=300; total=1000")!!
        assertNull(usage.used)
        assertNull(usage.remaining)
        assertNull(usage.progress)
    }

    @Test
    fun explicitZeroLimitsAreNotMissingData() {
        val usage = parseSubscriptionUsage("UPLOAD=0; download=0; total=0; expire=0")!!
        assertEquals(0L, usage.total)
        assertEquals(0L, usage.expire)
        assertEquals(0L, usage.used)
        assertNull(usage.progress)
        assertNull(usage.remaining)
    }

    @Test
    fun exhaustedAndOverflowUsageDoNotWrap() {
        val usage = SubscriptionUsage(Long.MAX_VALUE, 9, 1000, null)
        assertEquals(Long.MAX_VALUE, usage.used)
        assertEquals(0L, usage.remaining)
        assertEquals(1f, usage.progress!!, 0f)
    }

    @Test
    fun duplicateOrMalformedFieldsCannotInventQuota() {
        val usage =
            parseSubscriptionUsage(
                "download=20; upload=10; total=100; total=200; expire= 123 ; junk=4"
            )!!
        assertNull(usage.total)
        assertEquals(123L, usage.expire)
        assertEquals(30L, usage.used)
    }
}
