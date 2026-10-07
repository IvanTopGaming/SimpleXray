package com.simplexray.an.core.files

import android.app.Application
import android.net.Uri
import com.simplexray.an.core.files.config.ConfigFileStore
import com.simplexray.an.core.files.rules.RuleFileStore
import com.simplexray.an.prefs.Preferences
import java.io.File
import java.io.InputStream

class FileManager(application: Application, prefs: Preferences) {
    private val configs = ConfigFileStore(application, prefs)
    private val rules = RuleFileStore(application, prefs)

    suspend fun createConfigFile(): String? = configs.createConfigFile()

    suspend fun importConfigFromClipboard(): String? = configs.importConfigFromClipboard()

    suspend fun importConfigFromContent(content: String, name: String? = null): String? =
        configs.importConfigFromContent(content, name)

    suspend fun deleteConfigFile(fileToDelete: File): Boolean =
        configs.deleteConfigFile(fileToDelete)

    suspend fun importRuleFile(uri: Uri, filename: String): Boolean =
        rules.importRuleFile(uri, filename)

    suspend fun saveRuleFile(
        inputStream: InputStream,
        filename: String,
        onProgress: (Int) -> Unit,
    ): Boolean = rules.saveRuleFile(inputStream, filename, onProgress)

    fun getRuleFileSummary(filename: String): String = rules.getRuleFileSummary(filename)

    suspend fun renameConfigFile(oldFile: File, newFile: File, newContent: String): Boolean =
        configs.renameConfigFile(oldFile, newFile, newContent)

    companion object {
        const val TAG = "FileManager"
    }
}
