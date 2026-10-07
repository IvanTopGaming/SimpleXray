package com.simplexray.an.feature.dns.model

enum class DnsQueryStrategy(val configValue: String) {
    AUTO("UseIP"),
    IPV4("UseIPv4"),
    IPV6("UseIPv6"),
}
