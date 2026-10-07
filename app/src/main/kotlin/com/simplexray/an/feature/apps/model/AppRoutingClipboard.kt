package com.simplexray.an.feature.apps.model

import com.simplexray.an.BuildConfig

data class AppRoutingClipboard(val mode: AppRoutingMode, val packages: Set<String>) {
    fun encode(): String {
        val header =
            when (mode) {
                AppRoutingMode.ALL -> "all"
                AppRoutingMode.EXCLUDE -> "true"
                AppRoutingMode.INCLUDE -> "false"
            }
        return (listOf(header) + packages.sorted()).joinToString("\n")
    }

    companion object {
        fun decode(text: String): AppRoutingClipboard {
            val lines = text.trim().lines().map { it.trim() }
            val mode =
                when (lines.firstOrNull()) {
                    "all" -> AppRoutingMode.ALL
                    "true" -> AppRoutingMode.EXCLUDE
                    "false" -> AppRoutingMode.INCLUDE
                    else -> throw IllegalArgumentException("Неизвестный режим приложений")
                }
            val names = lines.drop(1)
            require(
                names.all {
                    it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))
                }
            ) {
                "Некорректное имя пакета приложения"
            }
            val packages = names.filterNot { it == BuildConfig.APPLICATION_ID }.toSet()
            require(mode != AppRoutingMode.INCLUDE || packages.isNotEmpty()) {
                "Выберите хотя бы одно приложение для VPN."
            }
            return AppRoutingClipboard(mode, packages)
        }
    }
}
