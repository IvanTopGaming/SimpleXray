package com.simplexray.an.feature.servers.importing

import android.content.Context

class VlessLinkConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean = content.startsWith("vless://")

    override fun convert(context: Context, content: String): Result<DetectedConfig> = runCatching {
        VlessConfigParser.parse(content)
    }
}
