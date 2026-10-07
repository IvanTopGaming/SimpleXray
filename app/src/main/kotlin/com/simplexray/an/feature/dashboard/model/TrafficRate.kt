package com.simplexray.an.feature.dashboard.model

class TrafficRateTracker {
    private var previous: TrafficState? = null
    private var previousTime = 0L

    fun update(current: TrafficState?, now: Long): TrafficState {
        if (current == null) return TrafficState(0, 0)
        val before = previous
        val rates =
            if (
                before == null ||
                    current.uplink < before.uplink ||
                    current.downlink < before.downlink
            )
                TrafficState(0, 0)
            else
                TrafficState(
                    trafficRate(current.uplink, before.uplink, now - previousTime),
                    trafficRate(current.downlink, before.downlink, now - previousTime),
                )
        previous = current
        previousTime = now
        return rates
    }
}

fun trafficRate(current: Long, previous: Long?, elapsedMillis: Long): Long {
    if (previous == null || elapsedMillis <= 0 || current < previous) return 0L
    return ((current - previous).toDouble() * 1000 / elapsedMillis).toLong()
}
