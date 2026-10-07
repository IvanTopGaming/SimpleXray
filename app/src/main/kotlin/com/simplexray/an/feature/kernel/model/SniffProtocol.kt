package com.simplexray.an.feature.kernel.model

enum class SniffProtocol(val configValue: String) {
    HTTP("http"),
    TLS("tls"),
    QUIC("quic"),
}
