package com.simplexray.an.feature.apps.state

sealed class AppListViewUiEvent {
    data class ShowSnackbar(val message: String) : AppListViewUiEvent()
}
