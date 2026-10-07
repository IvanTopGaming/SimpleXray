package com.simplexray.an.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplexray.an.ui.theme.DisableDialogWindowAnimations

@Composable
fun LabDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DisableDialogWindowAnimations()
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
                .systemBarsPadding()
                .imePadding()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.pointerInput(Unit) { detectTapGestures {} },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(16.dp)) {
                    Text(
                        title,
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontSize = 19.sp,
                                lineHeight = 23.75.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )
                    if (error != null)
                        Text(
                            error,
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}
