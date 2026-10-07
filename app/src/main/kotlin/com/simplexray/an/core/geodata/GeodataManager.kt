package com.simplexray.an.core.geodata

import com.simplexray.an.core.geodata.io.GeodataFileCopy
import com.simplexray.an.core.geodata.validation.GeodataValidator
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class GeodataManager(
    private val directory: File,
    private val urlFor: (String) -> String,
    private val client: OkHttpClient = defaultClient,
) {
    suspend fun ensureAvailable(required: Set<String>, onProgress: (String) -> Unit = {}) {
        withAvailable(required, onProgress) {}
    }

    suspend fun <T> withAvailable(
        required: Set<String>,
        onProgress: (String) -> Unit = {},
        refresh: Set<String> = emptySet(),
        onPublished: (String) -> Unit = {},
        block: suspend () -> T,
    ): T =
        withContext(Dispatchers.IO) {
            required.forEach(::requireFilename)
            refresh.forEach(::requireFilename)
            withStorageLock {
                required.sorted().forEach { filename ->
                    val file = File(directory, filename)
                    if (filename in refresh || !file.isFile || file.length() == 0L) {
                        download(filename, onProgress)
                        onPublished(filename)
                    }
                }
                currentCoroutineContext().ensureActive()
                block()
            }
        }

    suspend fun update(filename: String, onProgress: (String) -> Unit = {}) {
        requireFilename(filename)
        withContext(Dispatchers.IO) { withStorageLock { download(filename, onProgress) } }
    }

    suspend fun replace(filename: String, input: InputStream, onProgress: (Int) -> Unit = {}) {
        try {
            requireFilename(filename)
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val cancellation =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                awaitCancellation()
                            } finally {
                                runCatching { input.close() }
                            }
                        }
                    try {
                        withStorageLock {
                            stage(filename) { temporary ->
                                input.use {
                                    GeodataFileCopy.copy(
                                        input,
                                        temporary,
                                        filename,
                                        -1L,
                                        onProgress,
                                    ) {}
                                }
                            }
                        }
                    } finally {
                        cancellation.cancel()
                    }
                }
            }
        } finally {
            runCatching { input.close() }
        }
    }

    private suspend fun <T> withStorageLock(block: suspend () -> T): T = mutex.withLock {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Не удалось создать папку баз")
        }
        RandomAccessFile(File(directory, ".geodata.lock"), "rw").use { lockFile ->
            val channel = lockFile.channel
            var lock: FileLock?
            do {
                currentCoroutineContext().ensureActive()
                lock =
                    try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                if (lock == null) delay(25)
            } while (lock == null)
            lock.use {
                currentCoroutineContext().ensureActive()
                directory.listFiles()?.forEach { file ->
                    if (file.isFile && stagingFilename.matches(file.name) && !file.delete()) {
                        throw IOException("Не удалось удалить временную базу ${file.name}")
                    }
                }
                block()
            }
        }
    }

    private suspend fun download(filename: String, onProgress: (String) -> Unit) {
        onProgress("Загрузка $filename: 0 байт")
        val call = client.newCall(Request.Builder().url(urlFor(filename)).build())
        call.timeout().timeout(3, TimeUnit.MINUTES)
        coroutineScope {
            val cancellation =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Не удалось загрузить $filename: HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Пустой ответ для $filename")
                    val length = body.contentLength()
                    if (length > MAX_BYTES) throw IOException("$filename превышает 128 МиБ")
                    stage(filename) { temporary ->
                        body.byteStream().use { input ->
                            GeodataFileCopy.copy(
                                input,
                                temporary,
                                filename,
                                length,
                                onProgress = onProgress,
                            )
                        }
                    }
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw IOException(
                    "Не удалось подготовить $filename. Проверь источник и повтори подключение: ${error.message}",
                    error,
                )
            } finally {
                cancellation.cancel()
            }
        }
    }

    private suspend fun stage(filename: String, write: suspend (File) -> Unit) {
        val temporary = File.createTempFile(".$filename-", ".tmp", directory)
        try {
            write(temporary)
            GeodataValidator.validate(temporary, filename)
            currentCoroutineContext().ensureActive()
            Files.move(
                temporary.toPath(),
                File(directory, filename).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun requireFilename(filename: String) {
        require(filename == "geoip.dat" || filename == "geosite.dat") { "Неизвестная база" }
    }

    companion object {
        private val mutex = Mutex()
        private val stagingFilename = Regex("""\.(geoip|geosite)\.dat-[0-9]+\.tmp""")
        private val defaultClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(3, TimeUnit.MINUTES)
                .build()
    }
}
