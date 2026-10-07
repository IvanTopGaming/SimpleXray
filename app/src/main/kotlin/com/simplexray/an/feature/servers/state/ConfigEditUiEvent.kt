package com.simplexray.an.feature.servers.state

sealed class ConfigEditUiEvent {
    data class ShowSnackbar(val message: String) : ConfigEditUiEvent()

    data class ShareContent(val content: String) : ConfigEditUiEvent()

    data object NavigateBack : ConfigEditUiEvent()
}
