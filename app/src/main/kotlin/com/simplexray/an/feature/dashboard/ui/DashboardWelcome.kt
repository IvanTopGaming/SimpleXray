package com.simplexray.an.feature.dashboard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabIcon

@Composable
internal fun DashboardWelcome(
    onAddSubscription: () -> Unit,
    onPaste: () -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Добро пожаловать\nв SimpleXray",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Чтобы начать, добавьте подписку вашего провайдера или ссылку на сервер.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        LabButton(
            "Добавить подписку",
            onAddSubscription,
            Modifier.fillMaxWidth(),
            primary = true,
            icon = "add",
        )
        Spacer(Modifier.height(16.dp))
        LabButton("Вставить из буфера", onPaste, Modifier.fillMaxWidth(), icon = "clipboard")
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onAddServer) {
            LabIcon("edit", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Настроить сервер вручную")
        }
    }
}
