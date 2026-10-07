package com.simplexray.an.feature.profile.state

import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.core.config.profile.ProfileOverrides
import com.simplexray.an.core.files.config.ServerConfigLock
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.server.RoutingServerSources
import com.simplexray.an.feature.subscriptions.data.SubscriptionManager
import com.simplexray.an.prefs.Preferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ProfileEditor(private val prefs: Preferences) {
    suspend fun preview(): String =
        withContext(Dispatchers.IO) {
            SubscriptionManager.configMutex.withLock {
                withFilesSnapshot {
                    val source =
                        requireNotNull(selectedSource()) {
                            "Выбери сервер на экране серверов, чтобы увидеть JSON профиля."
                        }
                    val routing = prefs.readRoutingPreset().routing
                    AppConfig(prefs)
                        .buildProfile(source, routing, serverSources = serverSources(routing))
                }
            }
        }

    suspend fun save(raw: String): String? =
        withContext(Dispatchers.IO) {
            SubscriptionManager.configMutex.withLock {
                val overrides = ProfileOverrides.decode(raw)
                val compiled = withFilesSnapshot {
                    selectedSource()?.let {
                        val routing = prefs.readRoutingPreset().routing
                        AppConfig(prefs)
                            .buildProfile(it, routing, overrides, serverSources(routing))
                    }
                }
                val encoded = overrides.encode()
                prefs.profileOverridesJson = encoded
                check(prefs.profileOverridesJson == encoded) {
                    "Не удалось сохранить переопределения. Попробуй ещё раз."
                }
                compiled
            }
        }

    private fun <T> withFilesSnapshot(block: () -> T): T {
        val directory = prefs.selectedConfigPath?.let { File(it).parentFile } ?: return block()
        return ServerConfigLock.withSnapshot(directory, block)
    }

    private fun serverSources(routing: RoutingSettings): Map<String, String> {
        val directory = prefs.selectedConfigPath?.let { File(it).parentFile }
        return if (directory == null) emptyMap()
        else RoutingServerSources.read(routing, directory, prefs.subscriptions)
    }

    private fun selectedSource(): String? {
        val path = prefs.selectedConfigPath?.takeIf(String::isNotBlank) ?: return null
        val file = File(path)
        require(file.isFile) { "Файл выбранного сервера недоступен. Выбери сервер заново." }
        return file.readText()
    }
}
