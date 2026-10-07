package com.simplexray.an.core.config.profile

import com.google.gson.JsonObject
import com.simplexray.an.core.config.json.StrictJson

class ProfileOverrides private constructor(private val sections: JsonObject) {
    internal val hasCustomDnsRouting: Boolean
        get() = sections.has("dns") || sections.has("routing")

    fun encode(): String = sections.toString()

    fun applyTo(source: String): String =
        if (sections.size() == 0) source else ProfileOverrideCompiler.apply(source, sections)

    companion object {
        fun decode(raw: String?): ProfileOverrides {
            val text = raw?.takeIf { it.isNotBlank() } ?: "{}"
            require(
                text.length <= 1_048_576 && text.toByteArray(Charsets.UTF_8).size <= 1_048_576
            ) {
                "JSON-переопределения превышают 1 МиБ"
            }
            val root = StrictJson.read(text, "Некорректный JSON-переопределений")
            require(root.isJsonObject) { "Переопределения должны быть JSON-объектом" }
            val sections = root.asJsonObject
            require(
                sections.keySet().all { it in setOf("log", "dns", "routing", "policy", "stats") }
            ) {
                "Разрешены только секции log, dns, routing, policy и stats"
            }
            sections.entrySet().forEach { (name, value) ->
                require(value.isJsonObject) { "Секция $name должна быть объектом" }
                ProfileOverrideValidation.validate(name, value.asJsonObject)
            }
            return ProfileOverrides(sections)
        }
    }
}
