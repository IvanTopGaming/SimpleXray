package com.simplexray.an.core.runtime.lifecycle

class CoreLifecycleGate {
    private var stopped = false
    private var generation = 0L

    @Synchronized fun next(): Long? = if (stopped) null else ++generation

    @Synchronized
    fun runIfCurrent(token: Long, action: () -> Unit): Boolean {
        if (stopped || token != generation) return false
        action()
        return true
    }

    @Synchronized
    fun stop(cleanup: () -> Unit) {
        if (stopped) return
        stopped = true
        generation++
        cleanup()
    }
}
