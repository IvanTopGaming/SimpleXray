package com.simplexray.an.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

@Composable
fun DisableDialogWindowAnimations() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setWindowAnimations(0) }
}
