package com.simplexray.an.feature.servers.ui.manual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.servers.manual.model.ManualValue
import com.simplexray.an.feature.servers.manual.schema.ManualField
import com.simplexray.an.feature.servers.manual.schema.ManualSchemaRegistry
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabField
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.settings.LabSettingDescription

internal val LocalManualEditorEnabled = staticCompositionLocalOf { true }

@Composable
fun ManualObjectFieldsEditor(
    fields: List<ManualField>,
    value: ManualValue,
    registry: ManualSchemaRegistry,
    selectors: Map<String, String> = emptyMap(),
    pathPrefix: String = "manual",
    enabled: Boolean = true,
    onChange: (ManualValue) -> Unit,
) {
    CompositionLocalProvider(LocalManualEditorEnabled provides enabled) {
        ManualObjectFields(fields, value, registry, selectors, pathPrefix, 0, onChange)
    }
}

@Composable
internal fun ManualObjectFields(
    fields: List<ManualField>,
    value: ManualValue,
    registry: ManualSchemaRegistry,
    selectors: Map<String, String>,
    path: String,
    depth: Int,
    onChange: (ManualValue) -> Unit,
) {
    if (depth >= 64) {
        LabSettingDescription("Достигнута максимальная глубина вложенных параметров.")
        return
    }
    val inherited = registry.selectors(fields, value, selectors)
    val visible = fields.filter { it.visible(inherited) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        visible.forEach { field ->
            key(path, field.key) {
                ManualFieldEditor(
                    field,
                    value.field(field.key),
                    registry,
                    inherited,
                    "$path.${field.key}",
                    depth + 1,
                ) {
                    onChange(value.withField(field.key, it))
                }
            }
        }
    }
}

@Composable
internal fun ManualFieldEditor(
    field: ManualField,
    value: ManualValue?,
    registry: ManualSchemaRegistry,
    selectors: Map<String, String>,
    path: String,
    depth: Int,
    onChange: (ManualValue?) -> Unit,
) {
    if (depth >= 64) {
        LabSettingDescription("${field.label}: достигнута максимальная глубина вложения.")
        return
    }
    val active = value ?: registry.initial(field, selectors)
    val resolved = field.resolved(selectors, active)
    Column(
        Modifier.fillMaxWidth().testTag(path).semantics { contentDescription = field.label },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (!field.required && value != null) {
                IconButton(
                    onClick = { onChange(null) },
                    modifier = Modifier.testTag("$path.reset"),
                    enabled = LocalManualEditorEnabled.current,
                ) {
                    LabIcon("reset", "Сбросить ${field.label}")
                }
            }
        }
        LabSettingDescription(field.help)
        val variants = field.metadata["variants"]
        if (
            field.type == "union" &&
                !field.metadata.has("selector") &&
                variants?.isJsonArray == true
        ) {
            val candidates = variants.asJsonArray.map { ManualField(field.key, it.asJsonObject) }
            ManualChoice(
                active.type,
                candidates.map { it.type },
                "$path.type",
                "Формат значения",
                label = ::manualTypeLabel,
            ) { chosen ->
                candidates
                    .firstOrNull { it.type == chosen }
                    ?.let { onChange(registry.initial(it, selectors)) }
            }
        }
        when (resolved.type) {
            "object" ->
                Column(Modifier.padding(start = 12.dp)) {
                    ManualObjectFields(
                        registry.objectFields(resolved, active, selectors),
                        active,
                        registry,
                        selectors,
                        path,
                        depth + 1,
                    ) {
                        onChange(it)
                    }
                }
            "array" ->
                ManualArrayEditor(resolved, active, registry, selectors, path, depth + 1) {
                    onChange(it)
                }
            "map" ->
                ManualMapEditor(resolved, active, registry, selectors, path, depth + 1) {
                    onChange(it)
                }
            "boolean" ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = active.text == "true",
                        onCheckedChange = { onChange(ManualValue("boolean", it.toString())) },
                        modifier =
                            Modifier.testTag("$path.input").semantics {
                                contentDescription = field.label
                            },
                        enabled = LocalManualEditorEnabled.current,
                    )
                    Text(
                        if (active.text == "true") "Включено" else "Выключено",
                        Modifier.padding(start = 8.dp),
                    )
                }
            else -> {
                if (resolved.options.isNotEmpty()) {
                    ManualChoice(
                        active.text,
                        resolved.options,
                        "$path.input",
                        field.label,
                        label = { manualOptionLabel(field.key, it) },
                    ) {
                        onChange(ManualValue(resolved.type, it))
                    }
                } else {
                    ManualTextInput(
                        value = active.text,
                        onChange = { onChange(ManualValue(resolved.type, it)) },
                        label = field.label,
                        path = "$path.input",
                        secret = resolved.secret,
                        numeric = resolved.type == "integer",
                        multiline = resolved.metadata["multiline"]?.asBoolean == true,
                    )
                    if (resolved.type in listOf("integerRange", "portRanges")) {
                        LabSettingDescription(
                            if (resolved.type == "portRanges")
                                "Число или диапазоны через запятую: 443,1000-2000."
                            else "Число или диапазон: 100-200."
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ManualTextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    path: String,
    secret: Boolean = false,
    numeric: Boolean = false,
    multiline: Boolean = false,
) {
    val enabled = LocalManualEditorEnabled.current
    val modifier =
        Modifier.fillMaxWidth().testTag(path).semantics {
            contentDescription = label
            if (!enabled) disabled()
        }
    val options =
        KeyboardOptions(
            keyboardType =
                when {
                    secret -> KeyboardType.Password
                    numeric -> KeyboardType.Number
                    else -> KeyboardType.Text
                }
        )
    if (secret) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = modifier,
            label = { Text(label) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = options,
            singleLine = !multiline,
            enabled = enabled,
        )
    } else {
        LabField(
            value,
            { if (enabled) onChange(it) },
            modifier,
            placeholder = label,
            singleLine = !multiline,
            readOnly = !enabled,
            keyboardOptions = options,
        )
    }
}

@Composable
internal fun ManualChoice(
    value: String,
    options: List<String>,
    path: String,
    description: String,
    label: (String) -> String = { it.ifEmpty { "Без значения" } },
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val available = enabled && LocalManualEditorEnabled.current
    Column {
        LabButton(
            label(value),
            { expanded = true },
            Modifier.fillMaxWidth().testTag(path).semantics { contentDescription = description },
            icon = "arrow",
            compact = true,
            enabled = available,
        )
        DropdownMenu(expanded && available, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    modifier = Modifier.testTag("$path.option.$option"),
                    enabled = available,
                    leadingIcon =
                        if (option == value) {
                            { LabIcon("check", "Выбрано") }
                        } else null,
                )
            }
        }
    }
}

private fun manualOptionLabel(key: String, option: String): String =
    when (key) {
        "network" ->
            when (option) {
                "raw",
                "tcp" -> "TCP"
                "ws" -> "WebSocket"
                "kcp" -> "mKCP"
                "xhttp" -> "XHTTP"
                "grpc" -> "gRPC"
                "hysteria" -> "Hysteria2"
                else -> option
            }
        "security" ->
            when (option) {
                "none" -> "Без защиты"
                "tls" -> "TLS"
                "reality" -> "REALITY"
                else -> option
            }
        else -> option.ifEmpty { "Без значения" }
    }

private fun manualTypeLabel(type: String): String =
    when (type) {
        "string" -> "Текст"
        "integer" -> "Целое число"
        "boolean" -> "Да / нет"
        "array" -> "Список"
        "object" -> "Объект"
        "map" -> "Пары ключ — значение"
        "integerRange" -> "Число или диапазон"
        "portRanges" -> "Порты или диапазоны"
        else -> type
    }
