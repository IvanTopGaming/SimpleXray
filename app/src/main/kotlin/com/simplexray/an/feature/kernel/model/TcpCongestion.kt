package com.simplexray.an.feature.kernel.model

enum class TcpCongestion(val configValue: String) {
    SYSTEM(""),
    CUBIC("cubic"),
    BBR("bbr"),
}
