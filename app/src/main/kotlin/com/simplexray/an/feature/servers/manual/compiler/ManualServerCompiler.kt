package com.simplexray.an.feature.servers.manual.compiler

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.simplexray.an.feature.servers.manual.model.ManualDraftMigration
import com.simplexray.an.feature.servers.manual.model.ManualServerDraft
import com.simplexray.an.feature.servers.manual.model.ManualValue
import com.simplexray.an.feature.servers.manual.schema.ManualField
import com.simplexray.an.feature.servers.manual.schema.ManualSchemaRegistry
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder

class ManualServerCompiler(private val registry: ManualSchemaRegistry) {
    data class Result(val name: String, val json: String)

    fun compile(source: ManualServerDraft): Result {
        val draft = ManualDraftMigration.apply(source)
        val protocol = registry.protocol(draft.protocol)
        val settings =
            compileObject(registry.fields(protocol.schema), draft.settings, emptyMap(), 0)
        val stream =
            if (protocol.transport)
                compileObject(
                    registry.fields("StreamConfig"),
                    draft.stream ?: registry.initialDraft(protocol.id).stream!!,
                    emptyMap(),
                    0,
                )
            else null
        ManualClientValidation.validate(protocol.id, settings, stream)
        val outbound =
            JsonObject().apply {
                addProperty("tag", "proxy")
                addProperty("protocol", protocol.id)
                add("settings", settings)
                if (stream != null) add("streamSettings", stream)
            }
        val root = JsonObject().apply { add("outbounds", JsonArray().apply { add(outbound) }) }
        val json = root.toString()
        require(json.toByteArray(Charsets.UTF_8).size <= 1_048_576) {
            "Параметры сервера превышают 1 МиБ"
        }
        val name =
            draft.name.trim().ifEmpty {
                settings["address"]
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.takeIf { it.isNotBlank() } ?: protocol.label
            }
        return Result(name, json)
    }

    private fun compileObject(
        fields: List<ManualField>,
        value: ManualValue,
        inherited: Map<String, String>,
        depth: Int,
    ): JsonObject {
        require(depth <= 64) { "Слишком много вложенных параметров" }
        require(value.type == "object") { "Ожидалась группа параметров" }
        val selectors = registry.selectors(fields, value, inherited)
        val result = JsonObject()
        val queries = mutableListOf<Triple<String, String, String>>()
        for (field in fields.filter { it.visible(selectors) }) {
            val raw =
                value.field(field.key)
                    ?: if (field.required) registry.initial(field, selectors) else continue
            val compiled = compileValue(field, raw, selectors, depth + 1) ?: continue
            val target = field.metadata["queryTarget"]?.asString
            if (target != null) {
                queries.add(Triple(target, field.metadata["queryName"].asString, compiled.asString))
            } else result.add(field.key, compiled)
        }
        for ((target, key, valueText) in queries) {
            val existing = result[target]?.asString ?: "/"
            val fragment = existing.substringAfter('#', "")
            val beforeFragment = existing.substringBefore('#')
            val path = beforeFragment.substringBefore('?')
            val parameters =
                beforeFragment
                    .substringAfter('?', "")
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .filter {
                        runCatching { URLDecoder.decode(it.substringBefore('='), "UTF-8") }
                            .getOrDefault(it) != key
                    }
            val encoded =
                URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(valueText, "UTF-8")
            result.addProperty(
                target,
                path +
                    "?" +
                    (parameters + encoded).joinToString("&") +
                    if (fragment.isEmpty()) "" else "#$fragment",
            )
        }
        return result
    }

    private fun compileValue(
        definition: ManualField,
        value: ManualValue,
        selectors: Map<String, String>,
        depth: Int,
        preserveEmpty: Boolean = false,
    ): JsonElement? {
        require(depth <= 64) { "Слишком много вложенных параметров" }
        val field = definition.resolved(selectors, value)
        fun invalid(message: String): Nothing =
            throw IllegalArgumentException("${field.label}: $message")
        val scalar =
            field.type in listOf("string", "integer", "boolean", "integerRange", "portRanges")
        if (scalar && value.text.isEmpty() && !preserveEmpty) {
            if (!field.required) return null
            invalid("заполни поле")
        }
        val output: JsonElement =
            when (field.type) {
                "string" -> {
                    if (field.options.isNotEmpty() && value.text !in field.options)
                        invalid("выбери значение из списка")
                    JsonPrimitive(value.text)
                }
                "integer" -> {
                    val number =
                        value.text.trim().toBigIntegerOrNull() ?: invalid("нужно целое число")
                    field.metadata["minimum"]?.let {
                        if (number.toBigDecimal() < BigDecimal(it.asString))
                            invalid("число меньше допустимого")
                    }
                    field.metadata["maximum"]?.let {
                        if (number.toBigDecimal() > BigDecimal(it.asString))
                            invalid("число больше допустимого")
                    }
                    if (field.options.isNotEmpty() && number.toString() !in field.options)
                        invalid("выбери значение из списка")
                    JsonPrimitive(number)
                }
                "boolean" ->
                    JsonPrimitive(
                        value.text.toBooleanStrictOrNull() ?: invalid("нужен переключатель да/нет")
                    )
                "integerRange" -> {
                    val text = value.text.trim()
                    val match =
                        Regex("(-?\\d+)(?:-(-?\\d+))?").matchEntire(text)
                            ?: invalid("укажи число или диапазон минимум-максимум")
                    val first =
                        match.groupValues[1].toBigIntegerOrNull()
                            ?: invalid("некорректный диапазон")
                    val last =
                        match.groupValues[2].takeIf { it.isNotEmpty() }?.toBigIntegerOrNull()
                            ?: first
                    if (first > last) invalid("минимум больше максимума")
                    field.metadata["minimum"]?.let {
                        if (first.toBigDecimal() < BigDecimal(it.asString))
                            invalid("диапазон меньше допустимого")
                    }
                    field.metadata["maximum"]?.let {
                        if (last.toBigDecimal() > BigDecimal(it.asString))
                            invalid("диапазон больше допустимого")
                    }
                    if (match.groupValues[2].isEmpty()) JsonPrimitive(first)
                    else JsonPrimitive(text)
                }
                "portRanges" -> {
                    val parts = value.text.split(',').map(String::trim)
                    for (part in parts) {
                        val numbers =
                            part.split('-').map {
                                it.toIntOrNull()
                                    ?: invalid("укажи порты или диапазоны через запятую")
                            }
                        if (
                            numbers.size !in 1..2 ||
                                numbers.any { it !in 1..65535 } ||
                                numbers.first() > numbers.last()
                        )
                            invalid("порты должны быть от 1 до 65535")
                    }
                    JsonPrimitive(parts.joinToString(","))
                }
                "object" ->
                    compileObject(
                        registry.objectFields(field, value, selectors),
                        value,
                        selectors,
                        depth,
                    )
                "array" -> {
                    if (value.type != "array") invalid("нужен список")
                    field.metadata["minItems"]?.let {
                        if (value.items.size < it.asInt) invalid("добавь элемент списка")
                    }
                    field.metadata["allowedLengths"]?.let { lengths ->
                        if (lengths.asJsonArray.none { it.asInt == value.items.size })
                            invalid("некорректное число элементов")
                    }
                    val itemField = field.item ?: invalid("не задан тип элементов")
                    val items =
                        value.items.map {
                            compileValue(itemField, it, selectors, depth + 1, true)
                                ?: invalid("заполни элемент списка")
                        }
                    if (items.size >= 4)
                        field.metadata.getAsJsonObject("positionalMinimum")?.entrySet()?.forEach {
                            (index, minimum) ->
                            if (items[index.toInt()].asBigInteger < BigInteger(minimum.asString))
                                invalid("недопустимые тестовые значения")
                        }
                    JsonArray().apply { items.forEach(::add) }
                }
                "map" -> {
                    val entries =
                        if (value.type == "map")
                            value.items.map {
                                val key = it.field("key")?.text.orEmpty()
                                key to (it.field("value") ?: invalid("заполни значение"))
                            }
                        else value.fields.toList()
                    if (entries.any { it.first.isBlank() }) invalid("имя не может быть пустым")
                    if (entries.map { it.first }.distinct().size != entries.size)
                        invalid("имена не должны повторяться")
                    val valueField = field.mapValue ?: invalid("не задан тип значения")
                    JsonObject().apply {
                        entries.forEach { (key, child) ->
                            add(key, compileValue(valueField, child, selectors, depth + 1, true))
                        }
                    }
                }
                else -> invalid("выбери тип значения")
            }
        return when (field.metadata["serialize"]?.asString) {
            "pemLines" ->
                JsonArray().apply {
                    output.asString
                        .lineSequence()
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .forEach { add(it) }
                }
            "decimalString" -> JsonPrimitive(output.asString)
            "commaSeparated" -> JsonPrimitive(output.asJsonArray.joinToString(",") { it.asString })
            "colonSeparated" -> JsonPrimitive(output.asJsonArray.joinToString(":") { it.asString })
            else -> output
        }
    }
}
