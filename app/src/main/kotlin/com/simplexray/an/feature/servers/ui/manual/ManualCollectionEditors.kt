package com.simplexray.an.feature.servers.ui.manual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.servers.manual.model.ManualValue
import com.simplexray.an.feature.servers.manual.schema.ManualField
import com.simplexray.an.feature.servers.manual.schema.ManualSchemaRegistry
import com.simplexray.an.ui.components.LabButton
import com.simplexray.an.ui.components.LabIcon
import com.simplexray.an.ui.components.settings.LabSettingDescription

@Composable
internal fun ManualArrayEditor(
    field: ManualField,
    value: ManualValue,
    registry: ManualSchemaRegistry,
    selectors: Map<String, String>,
    path: String,
    depth: Int,
    onChange: (ManualValue) -> Unit,
) {
    val item = field.item ?: return
    val resolved = item.resolved(selectors, value.items.firstOrNull())
    if (resolved.type !in listOf("object", "array", "map", "union") && item.type != "union") {
        ManualTextInput(
            value.items.joinToString("\n") { it.text },
            { text ->
                onChange(
                    ManualValue.arrayValue(
                        if (text.isEmpty()) emptyList()
                        else text.split('\n').map { ManualValue(resolved.type, it) }
                    )
                )
            },
            field.label,
            "$path.input",
            secret = field.secret || resolved.secret,
            multiline = true,
        )
        LabSettingDescription("По одному значению на строку.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        value.items.forEachIndexed { index, entry ->
            key(path, index) {
                ManualCollectionEntry(
                    "${item.label} ${index + 1}",
                    "$path.$index",
                    {
                        onChange(
                            value.copy(
                                items =
                                    value.items.filterIndexed { position, _ -> position != index }
                            )
                        )
                    },
                ) {
                    val requiredItem =
                        ManualField(
                            item.key,
                            item.metadata.deepCopy().apply { addProperty("required", true) },
                        )
                    ManualFieldEditor(
                        requiredItem,
                        entry,
                        registry,
                        selectors,
                        "$path.$index.value",
                        depth + 1,
                    ) { updated ->
                        if (updated != null)
                            onChange(
                                value.copy(
                                    items =
                                        value.items.mapIndexed { position, child ->
                                            if (position == index) updated else child
                                        }
                                )
                            )
                    }
                }
            }
        }
        LabButton(
            "Добавить элемент",
            { onChange(value.copy(items = value.items + registry.initial(item, selectors))) },
            Modifier.fillMaxWidth().testTag("$path.add"),
            icon = "add",
            compact = true,
            enabled = LocalManualEditorEnabled.current && depth < 63,
        )
    }
}

@Composable
internal fun ManualMapEditor(
    field: ManualField,
    value: ManualValue,
    registry: ManualSchemaRegistry,
    selectors: Map<String, String>,
    path: String,
    depth: Int,
    onChange: (ManualValue) -> Unit,
) {
    val descriptor = field.mapValue ?: return
    val rows =
        if (value.type == "map") value.items
        else
            value.fields.map { (name, child) ->
                ManualValue.objectValue(mapOf("key" to ManualValue(text = name), "value" to child))
            }
    fun update(index: Int, updated: ManualValue) {
        onChange(
            ManualValue(
                "map",
                items = rows.mapIndexed { position, row -> if (position == index) updated else row },
            )
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { index, row ->
            key(path, index) {
                ManualCollectionEntry(
                    "Пара ${index + 1}",
                    "$path.$index",
                    {
                        onChange(
                            ManualValue(
                                "map",
                                items = rows.filterIndexed { position, _ -> position != index },
                            )
                        )
                    },
                ) {
                    ManualTextInput(
                        row.field("key")?.text.orEmpty(),
                        { update(index, row.withField("key", ManualValue(text = it))) },
                        "Ключ",
                        "$path.$index.key",
                    )
                    val requiredValue =
                        ManualField(
                            descriptor.key,
                            descriptor.metadata.deepCopy().apply { addProperty("required", true) },
                        )
                    ManualFieldEditor(
                        requiredValue,
                        row.field("value"),
                        registry,
                        selectors,
                        "$path.$index.value",
                        depth + 1,
                    ) { updated ->
                        if (updated != null) update(index, row.withField("value", updated))
                    }
                }
            }
        }
        LabButton(
            "Добавить пару",
            {
                onChange(
                    ManualValue(
                        "map",
                        items =
                            rows +
                                ManualValue.objectValue(
                                    mapOf(
                                        "key" to ManualValue(),
                                        "value" to registry.initial(descriptor, selectors),
                                    )
                                ),
                    )
                )
            },
            Modifier.fillMaxWidth().testTag("$path.add"),
            icon = "add",
            compact = true,
            enabled = LocalManualEditorEnabled.current && depth < 63,
        )
    }
}

@Composable
private fun ManualCollectionEntry(
    title: String,
    path: String,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            IconButton(
                onClick = onRemove,
                modifier = Modifier.testTag("$path.remove"),
                enabled = LocalManualEditorEnabled.current,
            ) {
                LabIcon("delete", "Удалить $title", tint = MaterialTheme.colorScheme.error)
            }
        }
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}
