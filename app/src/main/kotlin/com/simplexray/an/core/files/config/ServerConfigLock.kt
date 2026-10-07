package com.simplexray.an.core.files.config

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object ServerConfigLock {
    private val processLock = ReentrantLock()

    fun <T> withSnapshot(directory: File, block: () -> T): T =
        processLock.withLock {
            RandomAccessFile(File(directory, ".server-configs.lock"), "rw").use { file ->
                file.channel.lock().use { block() }
            }
        }
}
