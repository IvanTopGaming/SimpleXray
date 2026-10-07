package com.simplexray.an.feature.subscriptions.data

import com.simplexray.an.feature.subscriptions.model.SubscriptionUpdateInterval

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.SubscriptionRefreshJobService

object SubscriptionRefreshScheduler {
    const val JOB_ID = 4101

    fun reconcile(context: Context) {
        val prefs = Preferences(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!prefs.autoUpdateSubscriptions || prefs.subscriptions.isEmpty()) {
            scheduler.cancel(JOB_ID)
            return
        }
        val interval =
            SubscriptionUpdateInterval.fromMinutes(prefs.subscriptionUpdateIntervalMinutes)
        val pending = scheduler.getPendingJob(JOB_ID)
        if (pending?.intervalMillis == interval.millis && pending.flexMillis == interval.flexMillis)
            return
        scheduler.schedule(
            JobInfo.Builder(
                    JOB_ID,
                    ComponentName(context, SubscriptionRefreshJobService::class.java),
                )
                .setPeriodic(interval.millis, interval.flexMillis)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(1800000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
        )
    }
}
