package com.simplexray.an.app.ui

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.service.TProxyService
import kotlinx.coroutines.launch

data class MainScreenLaunchers(
    val startConnection: () -> Unit,
    val geoipFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    val geositeFilePickerLauncher: ActivityResultLauncher<Array<String>>,
)

@Composable
fun rememberMainScreenLaunchers(mainViewModel: MainViewModel): MainScreenLaunchers {
    val scope = rememberCoroutineScope()

    val vpnPrepareLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            result: ActivityResult ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                mainViewModel.setControlMenuClickable(false)
                mainViewModel.startTProxyService(TProxyService.ACTION_CONNECT)
            } else {
                mainViewModel.setControlMenuClickable(true)
            }
        }

    val startConnection = rememberNotificationPermissionRequest {
        if (mainViewModel.settingsState.value.switches.disableVpn) {
            mainViewModel.startTProxyService(TProxyService.ACTION_START)
        } else {
            mainViewModel.prepareAndStartVpn(vpnPrepareLauncher)
        }
    }

    val geoipFilePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    mainViewModel.importRuleFile(uri, "geoip.dat")
                }
            } else {
                Log.d("MainActivity", "Geoip file picking cancelled or failed (URI is null).")
            }
        }

    val geositeFilePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    mainViewModel.importRuleFile(uri, "geosite.dat")
                }
            } else {
                Log.d("MainActivity", "Geosite file picking cancelled or failed (URI is null).")
            }
        }

    return MainScreenLaunchers(
        startConnection = startConnection,
        geoipFilePickerLauncher = geoipFilePickerLauncher,
        geositeFilePickerLauncher = geositeFilePickerLauncher,
    )
}
