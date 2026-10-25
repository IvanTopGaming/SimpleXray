package com.simplexray.an.core.geodata.io

import com.simplexray.an.core.geodata.GeodataProgress
import com.simplexray.an.core.geodata.MAX_BYTES
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object GeodataFileCopy {
    suspend fun copy(
        input: InputStream,
        target: File,
        filename: String,
        expected: Long,
        onBytes: (Int) -> Unit = {},
        onProgress: (GeodataProgress) -> Unit,
    ) {
        var total = 0L
        var lastProgress = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count =
                    try {
                        input.read(buffer)
                    } catch (error: IOException) {
                        currentCoroutineContext().ensureActive()
                        throw error
                    }
                if (count < 0) break
                total += count
                if (total > MAX_BYTES) throw IOException("$filename превышает 128 МиБ")
                output.write(buffer, 0, count)
                onBytes(count)
                val now = System.nanoTime()
                if (now - lastProgress >= TimeUnit.MILLISECONDS.toNanos(200)) {
                    onProgress(GeodataProgress(filename, total, expected))
                    lastProgress = now
                }
            }
            if (total == 0L) throw IOException("Пустая база $filename")
            if (expected >= 0L && total != expected) throw IOException("Неполная база $filename")
            currentCoroutineContext().ensureActive()
            output.fd.sync()
        }
        onProgress(GeodataProgress(filename, total, expected))
    }
}
