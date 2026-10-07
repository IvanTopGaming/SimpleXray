package com.simplexray.an.feature.dashboard

import com.simplexray.an.feature.dashboard.model.TrafficRateTracker
import com.simplexray.an.feature.dashboard.model.TrafficState
import com.simplexray.an.feature.dashboard.model.trafficRate
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficSpeedTest {
    @Test
    fun missingSamplesKeepLastSuccessfulTimeAndVolume() {
        val tracker = TrafficRateTracker()
        assertEquals(TrafficState(0, 0), tracker.update(TrafficState(1000, 2000), 1000))
        assertEquals(TrafficState(0, 0), tracker.update(null, 2000))
        assertEquals(TrafficState(0, 0), tracker.update(null, 3000))
        assertEquals(TrafficState(2000, 3000), tracker.update(TrafficState(7000, 11000), 4000))
    }

    @Test
    fun restartedCountersStartANewSpeedBaselineForBothDirections() {
        val tracker = TrafficRateTracker()
        tracker.update(TrafficState(1000, 2000), 1000)
        assertEquals(TrafficState(0, 0), tracker.update(TrafficState(10, 200000), 2000))
        assertEquals(TrafficState(100, 200), tracker.update(TrafficState(110, 200200), 3000))
    }

    @Test
    fun usesElapsedTimeInsteadOfAssumingOneSecond() {
        assertEquals(2000L, trafficRate(5000, 1000, 2000))
    }

    @Test
    fun coreRestartNeverProducesNegativeSpeed() {
        assertEquals(0L, trafficRate(10, 1000, 1000))
    }

    @Test
    fun missingPreviousSampleDoesNotShowTotalAsSpeed() {
        assertEquals(0L, trafficRate(5000, null, 1000))
    }

    @Test
    fun zeroIntervalDoesNotDivideByZero() {
        assertEquals(0L, trafficRate(5000, 1000, 0))
    }
}
