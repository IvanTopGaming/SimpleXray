package com.simplexray.an.core.runtime.validation

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class CoreConfigValidator(
    private val executable: File,
    private val assets: File,
    private val temporaryDirectory: File,
    private val timeoutMs: Long = 30_000,
    private val workingDirectory: File = assets,
) {
    suspend fun validate(config: String) =
        withContext(Dispatchers.IO) {
            val input = File.createTempFile("routing-check-", ".json", temporaryDirectory)
            var process: Process? = null
            try {
                input.writeText(config)
                ensureActive()
                process =
                    ProcessBuilder(executable.path, "run", "-test", "-c", input.path)
                        .directory(workingDirectory)
                        .redirectErrorStream(true)
                        .redirectOutput(File("/dev/null"))
                        .apply {
                            environment()["XRAY_LOCATION_ASSET"] = assets.path
                            environment().remove("XRAY_MPH_CACHE")
                        }
                        .start()
                val child = process
                withTimeout(timeoutMs) { while (child.isAlive) delay(25) }
                require(child.exitValue() == 0) {
                    "Xray отклонил конфигурацию. Проверь сервер и правила."
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw IllegalArgumentException("Xray не завершил проверку за отведённое время")
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (_: Exception) {
                throw IllegalArgumentException("Не удалось запустить проверку Xray")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    process?.let { child ->
                        child.destroy()
                        if (!child.waitFor(1, TimeUnit.SECONDS)) {
                            child.destroyForcibly()
                            child.waitFor(1, TimeUnit.SECONDS)
                        }
                    }
                    input.delete()
                }
            }
        }
}
