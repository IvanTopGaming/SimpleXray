package com.simplexray.an.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
internal fun rememberNotificationPermissionRequest(onContinue: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val continueConnection by rememberUpdatedState(onContinue)
    var requesting by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (requesting) {
                requesting = false
                continueConnection()
            }
        }
    return {
        if (!requesting) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
            ) {
                requesting = true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                continueConnection()
            }
        }
    }
}
