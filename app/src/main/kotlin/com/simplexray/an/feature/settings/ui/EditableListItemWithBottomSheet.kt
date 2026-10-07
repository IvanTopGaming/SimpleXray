package com.simplexray.an.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simplexray.an.R
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.settings.LabSettingValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditableListItemWithBottomSheet(
    headline: String,
    help: String,
    currentValue: String,
    onValueConfirmed: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    sheetState: SheetState,
    scope: CoroutineScope,
) {
    var showSheet by remember { mutableStateOf(false) }
    var tempValue by remember { mutableStateOf(currentValue) }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
            com.simplexray.an.ui.theme.DisableDialogWindowAnimations()
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedTextField(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    label = { Text(label) },
                    keyboardOptions = keyboardOptions,
                    isError = isError,
                    supportingText = {
                        if (isError) {
                            Text(text = errorMessage ?: "")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            scope
                                .launch { sheetState.hide() }
                                .invokeOnCompletion {
                                    if (!sheetState.isVisible) {
                                        showSheet = false
                                    }
                                }
                        }
                    ) {
                        LabIcon("close", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onValueConfirmed(tempValue)
                            scope
                                .launch { sheetState.hide() }
                                .invokeOnCompletion {
                                    if (!sheetState.isVisible) {
                                        showSheet = false
                                    }
                                }
                        }
                    ) {
                        LabIcon("check", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.confirm))
                    }
                }
            }
        }
    }

    LabSettingValue(headline, currentValue, help, enabled, isError) {
        tempValue = currentValue
        showSheet = true
    }
    if (isError && errorMessage != null) {
        Text(
            errorMessage,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
