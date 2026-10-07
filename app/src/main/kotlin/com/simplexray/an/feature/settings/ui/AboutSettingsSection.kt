package com.simplexray.an.feature.settings.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simplexray.an.R
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.feature.settings.state.SettingsState
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.settings.LabSettingDivider
import com.simplexray.an.ui.components.settings.LabSettingGroup
import com.simplexray.an.ui.components.settings.LabSettingNote
import com.simplexray.an.ui.components.settings.LabSettingValue

@Composable
internal fun AboutSettingsSection(
    mainViewModel: MainViewModel,
    settingsState: SettingsState,
    isCheckingForUpdates: Boolean,
) {
    val context = LocalContext.current
    LabSettingGroup("О приложении") {
        Text(
            stringResource(R.string.version),
            Modifier.padding(top = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        LabSettingNote(settingsState.info.appVersion)
        LabButton(
            if (isCheckingForUpdates) "Проверяем…" else stringResource(R.string.check_for_updates),
            mainViewModel::checkForUpdates,
            enabled = !isCheckingForUpdates,
            modifier = Modifier.padding(vertical = 14.dp),
            icon = "refresh",
        )
        LabSettingDivider()
        Text(
            stringResource(R.string.kernel),
            Modifier.padding(top = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        LabSettingNote(settingsState.info.kernelVersion)
        Spacer(Modifier.height(14.dp))
        LabSettingDivider()
        LabSettingValue(
            stringResource(R.string.source),
            stringResource(R.string.open_source),
            help = "Открыть исходный код проекта на GitHub.",
        ) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.source_url)))
            )
        }
    }
}
