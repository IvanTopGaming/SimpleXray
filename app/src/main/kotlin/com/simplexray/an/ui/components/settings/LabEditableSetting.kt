package com.simplexray.an.ui.components.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import com.simplexray.an.feature.settings.state.InputFieldState
import com.simplexray.an.feature.settings.ui.EditableListItemWithBottomSheet
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LabEditableSetting(
    title: String,
    field: InputFieldState,
    onUpdate: (String) -> Unit,
    sheetState: SheetState,
    scope: CoroutineScope,
    help: String,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    EditableListItemWithBottomSheet(
        headline = title,
        help = help,
        currentValue = field.value,
        onValueConfirmed = onUpdate,
        label = title,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        isError = !field.isValid,
        errorMessage = field.error,
        enabled = enabled,
        sheetState = sheetState,
        scope = scope,
    )
}
