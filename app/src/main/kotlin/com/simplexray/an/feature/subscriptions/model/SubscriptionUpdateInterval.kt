package com.simplexray.an.feature.subscriptions.model

enum class SubscriptionUpdateInterval(val minutes: Int) {
    FIFTEEN_MINUTES(15),
    ONE_HOUR(60),
    SIX_HOURS(360),
    ONE_DAY(1440);

    val millis: Long
        get() = minutes * 60000L

    val flexMillis: Long
        get() = maxOf(300000L, millis / 4)

    companion object {
        fun fromMinutes(minutes: Int?): SubscriptionUpdateInterval =
            entries.firstOrNull { it.minutes == minutes } ?: ONE_HOUR
    }
}
