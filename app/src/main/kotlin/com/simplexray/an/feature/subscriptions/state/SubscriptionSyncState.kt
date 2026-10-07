package com.simplexray.an.feature.subscriptions.state

data class SubscriptionSyncState(
    val syncing: Boolean = false,
    val error: String? = null,
)
