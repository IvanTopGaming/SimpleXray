package com.simplexray.an.core.config.routing

import java.security.MessageDigest

object RoutingServerTags {
    fun outbound(blockId: String): String =
        "__sx_route_" +
            MessageDigest.getInstance("SHA-256")
                .digest(blockId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun isPrimary(tag: String?): Boolean = tag?.matches(Regex("__sx_route_[a-f0-9]{64}")) == true

    fun isOwned(tag: String?): Boolean =
        tag?.matches(Regex("__sx_route_[a-f0-9]{64}(?:_chain_[0-9]+)?")) == true
}
