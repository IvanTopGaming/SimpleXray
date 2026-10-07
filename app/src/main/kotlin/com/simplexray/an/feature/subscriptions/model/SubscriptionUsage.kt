package com.simplexray.an.feature.subscriptions.model

data class SubscriptionUsage(
    val upload: Long?,
    val download: Long?,
    val total: Long?,
    val expire: Long?,
) {
    val used: Long?
        get() {
            val up = upload?.takeIf { it >= 0 } ?: return null
            val down = download?.takeIf { it >= 0 } ?: return null
            return if (Long.MAX_VALUE - up < down) Long.MAX_VALUE else up + down
        }

    val remaining: Long?
        get() {
            val limit = total?.takeIf { it > 0 } ?: return null
            return used?.let { (limit - it).coerceAtLeast(0) }
        }

    val progress: Float?
        get() {
            val limit = total?.takeIf { it > 0 } ?: return null
            return used?.let { (it.toDouble() / limit).coerceIn(0.0, 1.0).toFloat() }
        }
}
