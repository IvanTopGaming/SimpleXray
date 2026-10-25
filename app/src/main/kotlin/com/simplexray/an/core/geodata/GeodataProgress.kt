package com.simplexray.an.core.geodata

data class GeodataProgress(val filename: String, val bytes: Long, val total: Long) {
    val fraction: Float?
        get() = if (total > 0L) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}
