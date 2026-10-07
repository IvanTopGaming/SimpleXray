package com.simplexray.an.feature.settings.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simplexray.an.R
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.ui.components.LabIcon

@Composable
internal fun AppUpdateDialog(mainViewModel: MainViewModel, newVersionTag: String?) {
    AlertDialog(
        onDismissRequest = { mainViewModel.clearNewVersionAvailable() },
        title = { Text(stringResource(R.string.new_version_available_title)) },
        text = { Text(stringResource(R.string.new_version_available_message, newVersionTag!!)) },
        confirmButton = {
            TextButton(onClick = { mainViewModel.downloadNewVersion(newVersionTag!!) }) {
                LabIcon("download", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.download))
            }
        },
        dismissButton = {
            TextButton(onClick = { mainViewModel.clearNewVersionAvailable() }) {
                LabIcon("close", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(id = android.R.string.cancel))
            }
        },
    )
}
