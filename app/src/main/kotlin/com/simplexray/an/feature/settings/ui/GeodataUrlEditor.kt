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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simplexray.an.R
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.settings.LabSettingDescription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GeodataUrlEditor(
    ruleFileUrl: String,
    editingRuleFile: String?,
    sheetState: SheetState,
    scope: CoroutineScope,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onDownload: (String, String) -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = { onDismiss() }, sheetState = sheetState) {
        com.simplexray.an.ui.theme.DisableDialogWindowAnimations()
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            OutlinedTextField(
                value = ruleFileUrl,
                onValueChange = { onUrlChange(it) },
                label = { Text("URL") },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp),
                trailingIcon = {
                    val clipboardManager = LocalClipboard.current
                    IconButton(
                        onClick = {
                            scope.launch {
                                clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text.let {
                                    onUrlChange(it.toString())
                                }
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.paste),
                            contentDescription = "Paste",
                        )
                    }
                },
            )

            LabSettingDescription(
                "Ссылка на файл GeoIP или GeoSite для загрузки.",
                Modifier.padding(horizontal = 12.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = {
                    onUrlChange(
                        if (editingRuleFile == "geoip.dat") context.getString(R.string.geoip_url)
                        else context.getString(R.string.geosite_url)
                    )
                }
            ) {
                LabIcon("reset", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(id = R.string.restore_default_url))
            }

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
                                    onDismiss()
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
                        onDownload(ruleFileUrl, editingRuleFile!!)
                        scope
                            .launch { sheetState.hide() }
                            .invokeOnCompletion {
                                if (!sheetState.isVisible) {
                                    onDismiss()
                                }
                            }
                    }
                ) {
                    LabIcon("refresh", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.update))
                }
            }
        }
    }
}
