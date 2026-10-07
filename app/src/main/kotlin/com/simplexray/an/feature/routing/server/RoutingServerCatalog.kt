package com.simplexray.an.feature.routing.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.feature.routing.model.RoutingServerRef
import com.simplexray.an.feature.subscriptions.data.files.SubscriptionConfigFiles
import com.simplexray.an.feature.subscriptions.model.Subscription
import java.io.File
import java.security.MessageDigest

data class RoutingServerOption(val reference: RoutingServerRef, val group: String)

object RoutingServerCatalog {
    fun options(files: List<File>, subscriptions: List<Subscription>): List<RoutingServerOption> =
        files.filter(File::isFile).mapNotNull { file ->
            val owners = subscriptions.filter { file.name in it.files }
            if (owners.size > 1) return@mapNotNull null
            val subscription = owners.singleOrNull()
            val fingerprint = fingerprint(file) ?: return@mapNotNull null
            val name = subscription?.let { serverName(it, file.name) } ?: file.nameWithoutExtension
            val reference =
                RoutingServerRef(
                    fileName = file.name,
                    name = name,
                    subscriptionId = subscription?.id,
                    fingerprint = fingerprint,
                    matchFingerprint =
                        subscription != null &&
                            subscription.files.count { serverName(subscription, it) == name } > 1,
                )
            try {
                reference.validate()
                RoutingServerOption(reference, subscription?.displayName ?: "Ручные серверы")
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    fun resolve(ref: RoutingServerRef, files: List<File>, subscriptions: List<Subscription>): File =
        resolveSource(ref, files, subscriptions).file

    fun read(ref: RoutingServerRef, files: List<File>, subscriptions: List<Subscription>): String =
        resolveSource(ref, files, subscriptions).content

    private data class ServerSource(val file: File, val content: String)

    private fun source(file: File): ServerSource =
        try {
            ServerSource(file, file.readText())
        } catch (_: Exception) {
            throw IllegalArgumentException(
                "Не удалось прочитать сервер блока роутинга. Выбери сервер заново."
            )
        }

    private fun resolveSource(
        ref: RoutingServerRef,
        files: List<File>,
        subscriptions: List<Subscription>,
    ): ServerSource {
        ref.validate()
        if (ref.subscriptionId == null) {
            val owned = subscriptions.flatMap { it.files }.toSet()
            val file =
                files.singleOrNull { it.isFile && it.name == ref.fileName && it.name !in owned }
                    ?: throw IllegalArgumentException("Сервер недоступен. Выбери сервер заново")
            return source(file)
        }
        val subscription =
            subscriptions.singleOrNull { it.id == ref.subscriptionId }
                ?: throw IllegalArgumentException("Подписка недоступна. Выбери сервер заново")
        val otherOwned =
            subscriptions.filter { it.id != ref.subscriptionId }.flatMap { it.files }.toSet()
        val matches =
            files.filter {
                it.isFile &&
                    it.name in subscription.files &&
                    it.name !in otherOwned &&
                    serverName(subscription, it.name) == ref.name
            }
        if (matches.size == 1 && !ref.matchFingerprint) return source(matches.single())
        if (matches.isEmpty()) {
            throw IllegalArgumentException("Сервер недоступен. Выбери сервер заново")
        }
        return matches.map(::source).singleOrNull {
            ref.fingerprint.isNotEmpty() &&
                fingerprint(it.content)?.equals(ref.fingerprint, ignoreCase = true) == true
        } ?: throw IllegalArgumentException("Сервер не удалось определить. Выбери сервер заново")
    }

    private fun serverName(subscription: Subscription, fileName: String): String =
        SubscriptionConfigFiles.serverName(subscription, fileName)
            .removeSuffix(".json")
            .replace(Regex(" \\(([2-9]|[1-9][0-9]+)\\)$"), "")

    private fun fingerprint(file: File): String? =
        try {
            fingerprint(file.readText())
        } catch (_: Exception) {
            null
        }

    private fun fingerprint(content: String): String? =
        try {
            val profile =
                JsonParser.parseString(OwnedConfig.serverProfile(content))
                    .asJsonObject
                    .getAsJsonArray("outbounds")
            MessageDigest.getInstance("SHA-256")
                .digest(canonical(profile).toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (_: Exception) {
            null
        }

    private fun canonical(value: JsonElement): JsonElement =
        when {
            value.isJsonObject ->
                JsonObject().apply {
                    value.asJsonObject
                        .entrySet()
                        .sortedBy { it.key }
                        .forEach { (key, child) -> add(key, canonical(child)) }
                }
            value.isJsonArray ->
                JsonArray().apply { value.asJsonArray.forEach { add(canonical(it)) } }
            else -> value.deepCopy()
        }
}
