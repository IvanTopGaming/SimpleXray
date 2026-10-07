package com.simplexray.an.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LabField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value,
        onValueChange,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = placeholder },
        readOnly = readOnly,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        textStyle =
            MaterialTheme.typography.bodyLarge.copy(
                color = if (readOnly) colors.onSurfaceVariant else colors.onSurface,
                fontSize = 16.sp,
            ),
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { input ->
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = colors.surfaceContainer,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Box(
                    Modifier.defaultMinSize(minHeight = 48.dp).padding(9.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty())
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                        )
                    input()
                }
            }
        },
    )
}
