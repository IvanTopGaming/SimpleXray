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
                    subscriptionKey = subscription?.let(::subscriptionKey),
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
            owner(ref, subscriptions)
                ?: throw IllegalArgumentException("Подписка недоступна. Выбери сервер заново")
        val otherOwned =
            subscriptions.filter { it.id != subscription.id }.flatMap { it.files }.toSet()
        val owned =
            files.filter {
                it.isFile && it.name in subscription.files && it.name !in otherOwned
            }
        val named = owned.filter { serverName(subscription, it.name) == ref.name }
        if (named.size == 1 && !ref.matchFingerprint) return source(named.single())
        val namedSources = named.map(::source)
        return pinned(ref, namedSources)
            ?: pinned(ref, owned.filter { it !in named }.map(::source) + namedSources)
            ?: throw IllegalArgumentException(
                if (named.isEmpty()) "Сервер недоступен. Выбери сервер заново"
                else "Сервер не удалось определить. Выбери сервер заново"
            )
    }

    internal fun subscriptionKey(subscription: Subscription): String =
        sha256(subscription.url.trim())

    private fun owner(ref: RoutingServerRef, subscriptions: List<Subscription>): Subscription? =
        subscriptions.singleOrNull { it.id == ref.subscriptionId }
            ?: ref.subscriptionKey?.let { key ->
                subscriptions.singleOrNull { subscriptionKey(it) == key }
            }

    private fun pinned(ref: RoutingServerRef, sources: List<ServerSource>): ServerSource? =
        if (ref.fingerprint.isEmpty()) null
        else
            sources.singleOrNull {
                fingerprint(it.content)?.equals(ref.fingerprint, ignoreCase = true) == true
            }

    internal fun serverName(subscription: Subscription, fileName: String): String =
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
            sha256(canonical(profile).toString())
        } catch (_: Exception) {
            null
        }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
