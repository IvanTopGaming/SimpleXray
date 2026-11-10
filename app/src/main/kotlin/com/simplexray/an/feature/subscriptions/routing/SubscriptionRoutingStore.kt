package com.simplexray.an.feature.subscriptions.routing

import com.google.gson.Gson
import com.simplexray.an.core.geodata.GeodataFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SubscriptionRoutingStore(private val filesDir: File) {
    private val directory: File
        get() = File(filesDir, DIRECTORY)

    fun read(id: String): SubscriptionRouting? {
        val file = profile(id)?.takeIf(File::isFile) ?: return null
        return try {
            gson.fromJson(file.readText(), SubscriptionRouting::class.java).also { it.validate() }
        } catch (_: Exception) {
            null
        }
    }

    fun write(id: String, routing: SubscriptionRouting) {
        routing.validate()
        val target = requireNotNull(profile(id)) { "Некорректный идентификатор подписки" }
        write(target, gson.toJson(routing))
    }

    fun delete(id: String) {
        profile(id)?.delete()
        val prefix = GeodataFiles.subscriptionPrefix(id)
        filesDir
            .listFiles { file ->
                file.isFile && file.name.startsWith(prefix) && GeodataFiles.isSubscription(file.name)
            }
            ?.forEach { file ->
                file.delete()
                File(directory, "${file.name}$SOURCE").delete()
            }
        directory
            .listFiles { file -> file.isFile && file.name.startsWith(prefix) }
            ?.forEach(File::delete)
    }

    fun geodataSource(file: String): String? {
        require(GeodataFiles.isSubscription(file))
        return File(directory, "$file$SOURCE").takeIf(File::isFile)?.readText()
    }

    fun markGeodata(file: String, url: String) {
        require(GeodataFiles.isSubscription(file))
        write(File(directory, "$file$SOURCE"), url)
    }

    private fun profile(id: String): File? =
        if (id.matches(ID)) File(directory, "$id.json") else null

    private fun write(target: File, content: String) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("Не удалось сохранить роутинг подписки")
        }
        val temporary = File.createTempFile(".${target.name}-", ".tmp", directory)
        try {
            temporary.writeText(content)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val DIRECTORY = "subscription-routing"
        const val SOURCE = ".source"
        val ID = Regex("[A-Za-z0-9-]{1,100}")
        val gson = Gson()
    }
}
