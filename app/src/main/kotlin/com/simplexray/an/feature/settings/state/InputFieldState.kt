package com.simplexray.an.feature.settings.state

data class InputFieldState(
    val value: String,
    val error: String? = null,
    val isValid: Boolean = true,
)
