package com.simplexray.an.service

import android.app.ActivityManager
import android.app.job.JobParameters
import android.app.job.JobService
import com.simplexray.an.feature.subscriptions.data.SubscriptionManager
import com.simplexray.an.feature.subscriptions.data.SubscriptionRefreshScheduler
import com.simplexray.an.prefs.Preferences
import kotlinx.coroutines.*

class SubscriptionRefreshJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = Preferences(this)
        running = scope.launch {
            val manager =
                SubscriptionManager(application, prefs) {
                    @Suppress("DEPRECATION")
                    getSystemService(ActivityManager::class.java)
                        .getRunningServices(Int.MAX_VALUE)
                        .any { it.service.className == TProxyService::class.java.name }
                }
            val retry =
                try {
                    manager.refreshAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    true
                }
            jobFinished(params, retry)
            SubscriptionRefreshScheduler.reconcile(this@SubscriptionRefreshJobService)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return Preferences(this).autoUpdateSubscriptions
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
