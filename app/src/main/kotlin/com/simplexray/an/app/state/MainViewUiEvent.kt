package com.simplexray.an.app.state

import android.content.Intent

sealed class MainViewUiEvent {
    data class ShowSnackbar(val message: String) : MainViewUiEvent()

    data class ShareLauncher(val intent: Intent) : MainViewUiEvent()

    data class StartService(val intent: Intent) : MainViewUiEvent()

    data object RefreshConfigList : MainViewUiEvent()

    data class Navigate(val route: String) : MainViewUiEvent()
}
