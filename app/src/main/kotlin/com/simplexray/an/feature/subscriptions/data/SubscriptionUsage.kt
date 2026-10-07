package com.simplexray.an.feature.subscriptions.data

import com.simplexray.an.feature.subscriptions.model.SubscriptionUsage

fun subscriptionExpiryText(expire: Long?, nowSeconds: Long): String {
    if (expire == null || expire < 0) return "Срок действия неизвестен"
    if (expire == 0L) return "Без срока действия"
    if (expire <= nowSeconds) return "Срок действия истёк"
    val remaining = expire - nowSeconds.coerceAtLeast(0)
    val days = remaining / 86400 + if (remaining % 86400 == 0L) 0 else 1
    return "Продление через $days дн."
}

fun subscriptionBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    var value = bytes.toDouble()
    val units = listOf("КБ", "МБ", "ГБ", "ТБ", "ПБ", "ЭБ")
    var index = -1
    do {
        value /= 1024
        index++
    } while (value >= 1024 && index < units.lastIndex)
    return String.format(java.util.Locale("ru"), "%.1f %s", value, units[index])
}

fun parseSubscriptionUsage(header: String?): SubscriptionUsage? {
    if (header.isNullOrBlank()) return null
    val values =
        header
            .split(';')
            .mapNotNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2)
                    pair[0].trim().lowercase(java.util.Locale.ROOT) to pair[1].trim()
                else null
            }
            .groupBy({ it.first }, { it.second })
    fun number(key: String): Long? =
        values[key]
            ?.singleOrNull()
            ?.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
    val usage =
        SubscriptionUsage(number("upload"), number("download"), number("total"), number("expire"))
    return usage.takeIf {
        it.upload != null || it.download != null || it.total != null || it.expire != null
    }
}
