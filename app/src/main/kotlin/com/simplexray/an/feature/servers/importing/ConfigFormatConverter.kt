package com.simplexray.an.feature.servers.importing

import android.content.Context

interface ConfigFormatConverter {
    fun detect(content: String): Boolean

    fun convert(context: Context, content: String): Result<DetectedConfig>

    companion object {
        val knownImplementations =
            listOf(
                SimpleXrayFormatConverter(),
                ShareLinkConverter(VlessConfigParser::parse, "vless"),
                ShareLinkConverter(VmessConfigParser::parse, "vmess"),
                ShareLinkConverter(ShadowsocksConfigParser::parse, "ss"),
                ShareLinkConverter(TrojanConfigParser::parse, "trojan"),
                ShareLinkConverter(Hysteria2ConfigParser::parse, "hysteria2", "hy2"),
                ShareLinkConverter(SocksConfigParser::parse, "socks", "socks5"),
                ShareLinkConverter(WireguardConfigParser::parse, "wireguard", "wg"),
            )

        fun convertOrNull(context: Context, content: String): Result<DetectedConfig>? {
            for (implementation in knownImplementations) {
                if (implementation.detect(content)) return implementation.convert(context, content)
            }
            return null
        }

        fun convert(context: Context, content: String): Result<DetectedConfig> {
            return convertOrNull(context, content)
                ?: run {
                    return Result.success(
                        Pair("imported_share_" + System.currentTimeMillis(), content)
                    )
                }
        }
    }
}
