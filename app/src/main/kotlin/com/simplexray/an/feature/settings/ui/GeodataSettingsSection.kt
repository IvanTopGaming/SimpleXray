package com.simplexray.an.feature.settings.ui

import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.feature.settings.state.SettingsState
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.settings.LabSettingGroup
import com.simplexray.an.ui.components.settings.LabSettingNote
import com.simplexray.an.ui.components.settings.LabSettingValue

@Composable
internal fun GeodataSettingsSection(
    mainViewModel: MainViewModel,
    settingsState: SettingsState,
    geoipProgress: String?,
    geositeProgress: String?,
    geoipFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    geositeFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    onEditUrl: (String, String) -> Unit,
) {
    listOf("geoip.dat", "geosite.dat").forEach { fileName ->
        val isGeoip = fileName == "geoip.dat"
        val progress = if (isGeoip) geoipProgress else geositeProgress
        val currentUrl = if (isGeoip) settingsState.info.geoipUrl else settingsState.info.geositeUrl
        val summary =
            if (isGeoip) settingsState.info.geoipSummary else settingsState.info.geositeSummary
        val editUrl = {
            onEditUrl(fileName, currentUrl)
            Unit
        }
        LabSettingGroup(if (isGeoip) "GeoIP · IP-сети" else "GeoSite · домены") {
            LabSettingValue(
                "Источник базы",
                currentUrl,
                if (isGeoip) "Ссылка на файл с IP-сетями для правил."
                else "Ссылка на файл с доменами для правил.",
                onClick = editUrl,
            )
            LabSettingNote(progress ?: summary)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (progress != null) {
                    LabButton(
                        "Отменить",
                        { mainViewModel.cancelDownload(fileName) },
                        icon = "close",
                    )
                } else {
                    LabButton("Обновить", editUrl, icon = "refresh")
                    LabButton(
                        "Импорт",
                        {
                            if (isGeoip) geoipFilePickerLauncher.launch(arrayOf("*/*"))
                            else geositeFilePickerLauncher.launch(arrayOf("*/*"))
                        },
                        icon = "import",
                    )
                }
            }
        }
    }
}
