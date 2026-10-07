package com.simplexray.an.feature.subscriptions.model

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long,
    val files: List<String>,
    val displayTitle: String? = null,
    val usage: SubscriptionUsage? = null,
    val lastError: String? = null,
) {
    val displayName: String
        get() = displayTitle?.takeIf { it.isNotBlank() } ?: name
}
