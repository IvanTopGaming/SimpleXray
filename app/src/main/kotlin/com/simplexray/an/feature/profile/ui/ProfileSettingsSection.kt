package com.simplexray.an.feature.profile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.feature.profile.state.ProfileEditor
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.settings.LabSettingDescription
import com.simplexray.an.ui.components.settings.LabSettingGroup
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProfileSettingsSection(mainViewModel: MainViewModel) {
    val selected by mainViewModel.selectedConfigFile.collectAsStateWithLifecycle()
    ProfileSettingsSection(mainViewModel.prefs, selected)
}

@Composable
internal fun ProfileSettingsSection(prefs: Preferences, selectedConfigFile: File?) {
    val editor = remember(prefs) { ProfileEditor(prefs) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf(prefs.profileOverridesJson ?: "{}") }
    var preview by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(editor, selectedConfigFile) {
        busy = true
        preview = null
        error = null
        try {
            preview = editor.preview()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Не удалось собрать профиль. Проверь настройки и сервер."
        } finally {
            busy = false
        }
    }

    LabSettingGroup("Переопределения JSON") {
        Column(
            Modifier.padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LabSettingDescription("Заменяет секции целиком при подключении. {} — сброс.")
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    error = null
                    feedback = null
                },
                modifier =
                    Modifier.fillMaxWidth().testTag("profile-overrides").semantics {
                        contentDescription = "Переопределения"
                    },
                readOnly = busy,
                minLines = 6,
                maxLines = 12,
                textStyle =
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
            )
            error?.let {
                Text(
                    it,
                    Modifier.testTag("profile-error"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            feedback?.let { LabSettingDescription(it) }
            LabButton(
                "Сохранить переопределения",
                onClick = {
                    busy = true
                    error = null
                    feedback = null
                    scope.launch {
                        try {
                            preview = editor.save(draft)
                            feedback =
                                if (preview == null) "Сохранено. Выбери сервер для предпросмотра."
                                else "Сохранено. Применится после переподключения."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Не удалось сохранить переопределения."
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                enabled = !busy,
                icon = "save",
            )
        }
    }
    LabSettingGroup("Собранный профиль") {
        Column(
            Modifier.padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LabSettingDescription("Конфиг выбранного сервера с сохранёнными настройками.")
            preview?.let {
                OutlinedTextField(
                    value = it,
                    onValueChange = {},
                    modifier =
                        Modifier.fillMaxWidth().testTag("profile-preview").semantics {
                            contentDescription = "JSON профиля"
                        },
                    readOnly = true,
                    minLines = 6,
                    maxLines = 12,
                    textStyle =
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            LabButton(
                "Копировать JSON профиля",
                onClick = {
                    busy = true
                    error = null
                    feedback = null
                    scope.launch {
                        try {
                            val compiled = editor.preview()
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("Профиль SimpleXray", compiled)
                            )
                            preview = compiled
                            feedback = "JSON сохранённого профиля скопирован."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Не удалось скопировать профиль."
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && selectedConfigFile != null,
                icon = "clipboard",
            )
        }
    }
}
