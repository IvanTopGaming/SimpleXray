package com.simplexray.an.feature.servers.manual.model

import com.google.gson.Gson

data class ManualValue(
    val type: String = "string",
    val text: String = "",
    val fields: Map<String, ManualValue> = emptyMap(),
    val items: List<ManualValue> = emptyList(),
) {
    fun field(key: String): ManualValue? = fields[key]

    fun withField(key: String, value: ManualValue?): ManualValue =
        copy(
            fields =
                fields.toMutableMap().apply { if (value == null) remove(key) else put(key, value) }
        )

    fun selectors(inherited: Map<String, String>): Map<String, String> =
        inherited +
            fields
                .filterValues { it.type !in listOf("object", "array", "map") }
                .mapValues { it.value.text }

    companion object {
        fun objectValue(fields: Map<String, ManualValue> = emptyMap()) =
            ManualValue("object", fields = fields)

        fun arrayValue(items: List<ManualValue> = emptyList()) = ManualValue("array", items = items)
    }
}

data class ManualServerDraft(
    val name: String = "",
    val protocol: String = "vless",
    val settings: ManualValue = ManualValue.objectValue(),
    val stream: ManualValue? = null,
) {
    fun encode(): String = Gson().toJson(this)

    companion object {
        fun decode(text: String): ManualServerDraft =
            Gson().fromJson(text, ManualServerDraft::class.java)
    }
}
