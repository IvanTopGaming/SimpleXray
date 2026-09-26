package com.simplexray.an.viewmodel

data class SubscriptionSyncState(
    val syncing: Boolean = false,
    val error: String? = null
)
