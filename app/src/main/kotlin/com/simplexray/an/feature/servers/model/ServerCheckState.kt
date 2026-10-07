package com.simplexray.an.feature.servers.model

data class ServerCheckResult(val latencyMs: Long? = null, val error: String? = null)

data class ServerCheckState(
    val running: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val available: Int = 0,
    val unavailable: Int = 0,
    val skipped: Int = 0,
    val cancelled: Boolean = false,
    val results: Map<String, ServerCheckResult> = emptyMap(),
    val checking: Set<String> = emptySet(),
)
