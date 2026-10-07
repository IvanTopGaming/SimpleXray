package com.simplexray.an.feature.subscriptions

import android.app.Application
import android.app.job.JobScheduler
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.feature.subscriptions.data.SubscriptionRefreshScheduler
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.prefs.Preferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SubscriptionScheduleTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val prefs = Preferences(app)
    private val scheduler = app.getSystemService(JobScheduler::class.java)
    private val oldSubs = prefs.subscriptions
    private val oldEnabled = prefs.autoUpdateSubscriptions

    @Before
    fun setup() {
        prefs.subscriptions =
            listOf(
                Subscription(
                    "schedule-test",
                    "schedule-test",
                    "https://example.invalid",
                    0,
                    emptyList(),
                )
            )
        prefs.autoUpdateSubscriptions = true
        scheduler.cancel(SubscriptionRefreshScheduler.JOB_ID)
    }

    @After
    fun cleanup() {
        prefs.subscriptions = oldSubs
        prefs.autoUpdateSubscriptions = oldEnabled
        SubscriptionRefreshScheduler.reconcile(app)
    }

    @Test
    fun enabledSubscriptionsHaveOnePersistedHourlyNetworkJob() {
        SubscriptionRefreshScheduler.reconcile(app)
        SubscriptionRefreshScheduler.reconcile(app)
        val jobs = scheduler.allPendingJobs.filter { it.id == SubscriptionRefreshScheduler.JOB_ID }
        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertEquals(3600000L, job.intervalMillis)
        assertEquals(900000L, job.flexMillis)
        assertTrue(job.isPersisted)
        assertNotNull(job.requiredNetwork)
    }

    @Test
    fun disablingCancelsPendingRefreshAndPersistsChoice() {
        SubscriptionRefreshScheduler.reconcile(app)
        prefs.autoUpdateSubscriptions = false
        SubscriptionRefreshScheduler.reconcile(app)
        assertNull(scheduler.getPendingJob(SubscriptionRefreshScheduler.JOB_ID))
        assertFalse(Preferences(app).autoUpdateSubscriptions)
        prefs.autoUpdateSubscriptions = true
        assertTrue(Preferences(app).autoUpdateSubscriptions)
    }

    @Test
    fun removingLastSubscriptionCancelsJob() {
        SubscriptionRefreshScheduler.reconcile(app)
        prefs.subscriptions = emptyList()
        SubscriptionRefreshScheduler.reconcile(app)
        assertNull(scheduler.getPendingJob(SubscriptionRefreshScheduler.JOB_ID))
    }
}
