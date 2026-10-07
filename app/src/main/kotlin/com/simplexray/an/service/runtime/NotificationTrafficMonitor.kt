package com.simplexray.an.service.runtime

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.simplexray.an.core.runtime.stats.CoreStatsClient
import com.simplexray.an.feature.dashboard.model.TrafficRateTracker
import com.simplexray.an.feature.dashboard.model.TrafficState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal class NotificationTrafficMonitor(
    private val context: Context,
    private val notifications: ServiceNotifications,
) {
    suspend fun run(
        client: CoreStatsClient,
        isRunning: () -> Boolean,
        publish: (TrafficState?, TrafficState) -> Unit,
    ) {
        val power = context.getSystemService(PowerManager::class.java)
        var rates = TrafficRateTracker()
        while (currentCoroutineContext().isActive && isRunning()) {
            if (!power.isInteractive || !notifications.canUpdate()) {
                rates = TrafficRateTracker()
                delay(5000)
                continue
            }
            val traffic = client.getTraffic()
            if (!currentCoroutineContext().isActive || !isRunning()) return
            if (traffic == null) rates = TrafficRateTracker()
            publish(traffic, rates.update(traffic, SystemClock.elapsedRealtime()))
            delay(2000)
        }
    }
}
