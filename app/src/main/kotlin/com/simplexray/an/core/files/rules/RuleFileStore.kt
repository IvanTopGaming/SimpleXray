package com.simplexray.an.core.files.rules

import android.app.Application
import android.net.Uri
import android.util.Log
import com.simplexray.an.core.geodata.GeodataManager
import com.simplexray.an.prefs.Preferences
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class RuleFileStore(private val application: Application, private val prefs: Preferences) {
    private fun geodataManager() =
        GeodataManager(
            application.filesDir,
            { name ->
                if (name == "geoip.dat") prefs.geoipUrl else prefs.geositeUrl
            },
        )

    suspend fun importRuleFile(uri: Uri, filename: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                application.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть файл базы" }
                    geodataManager().replace(filename, input)
                }
                markRuleFileImported(filename)
                true
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }

    suspend fun saveRuleFile(
        inputStream: InputStream,
        filename: String,
        onProgress: (Int) -> Unit,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                geodataManager().replace(filename, inputStream, onProgress)
                markRuleFileImported(filename)
                true
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }

    private fun markRuleFileImported(filename: String) {
        when (filename) {
            "geoip.dat" -> prefs.customGeoipImported = true
            "geosite.dat" -> prefs.customGeositeImported = true
        }
    }

    fun getRuleFileSummary(filename: String): String {
        Log.d(TAG, "getRuleFileSummary called with filename: $filename")
        val file = File(application.filesDir, filename)
        return if (file.isFile && file.length() > 0) {
            val lastModified = file.lastModified()
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            val date = sdf.format(Date(lastModified))
            val size = formatFileSize(file.length())
            "$date | $size"
        } else {
            "Не скачана"
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return String.format(
            Locale.getDefault(),
            "%.1f %s",
            size / 1024.0.pow(digitGroups.toDouble()),
            units[digitGroups],
        )
    }

    companion object {
        private const val TAG = "FileManager"
    }
}
