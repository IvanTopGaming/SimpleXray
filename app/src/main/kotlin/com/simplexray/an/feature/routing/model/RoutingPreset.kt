package com.simplexray.an.feature.routing.model

import com.simplexray.an.feature.routing.preset.RoutingPresetCodec
import java.io.InputStream

data class RoutingPreset(
    val routing: RoutingSettings,
    val geoipUrl: String,
    val geositeUrl: String,
) {
    fun validate() {
        RoutingPresetCodec.encode(this)
    }

    fun encode(): String = RoutingPresetCodec.encode(this)

    fun encodeLink(): String = RoutingPresetCodec.encodeLink(this)

    companion object {
        const val FORMAT = "simplexray-routing"

        fun decode(raw: String): RoutingPreset = RoutingPresetCodec.decode(raw)

        fun detect(raw: String): RoutingPreset? = RoutingPresetCodec.detect(raw)

        fun readText(input: InputStream): String = RoutingPresetCodec.readText(input)
    }
}
