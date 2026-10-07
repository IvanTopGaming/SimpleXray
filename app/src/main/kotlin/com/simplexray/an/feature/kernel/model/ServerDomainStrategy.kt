package com.simplexray.an.feature.kernel.model

enum class ServerDomainStrategy(val configValue: String) {
    AS_IS("AsIs"),
    USE_IP("UseIP"),
    USE_IPV4("UseIPv4"),
    USE_IPV6("UseIPv6"),
    USE_IPV4V6("UseIPv4v6"),
    USE_IPV6V4("UseIPv6v4"),
}
