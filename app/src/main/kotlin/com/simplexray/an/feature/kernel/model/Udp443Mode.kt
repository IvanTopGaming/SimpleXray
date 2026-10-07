package com.simplexray.an.feature.kernel.model

enum class Udp443Mode(val configValue: String) {
    REJECT("reject"),
    ALLOW("allow"),
    SKIP("skip"),
}
