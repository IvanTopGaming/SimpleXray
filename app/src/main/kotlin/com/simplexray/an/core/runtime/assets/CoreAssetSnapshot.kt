package com.simplexray.an.core.runtime.assets

import java.io.Closeable
import java.io.File
import java.nio.file.Files

class CoreAssetSnapshot private constructor(val directory: File) : Closeable {
    override fun close() {
        directory.deleteRecursively()
    }

    companion object {
        fun clearStale(cache: File) {
            cache
                .listFiles { file -> file.isDirectory && file.name.startsWith("core-assets-") }
                ?.forEach { it.deleteRecursively() }
        }

        fun create(source: File, cache: File, required: Set<String>): CoreAssetSnapshot {
            require(required.all { it == "geoip.dat" || it == "geosite.dat" })
            val directory = Files.createTempDirectory(cache.toPath(), "core-assets-").toFile()
            try {
                required.forEach { name ->
                    val original = File(source, name)
                    require(original.isFile && original.length() > 0) { "Нет базы $name" }
                    val destination = File(directory, name)
                    try {
                        Files.createLink(destination.toPath(), original.toPath())
                    } catch (_: Exception) {
                        original.copyTo(destination)
                    }
                }
                return CoreAssetSnapshot(directory)
            } catch (error: Exception) {
                directory.deleteRecursively()
                throw error
            }
        }
    }
}
